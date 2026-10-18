package com.robomwm.ai.matrixrobobot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCalibrationFamilyTest {

    @Test
    void familyForModelGroupsQwenAcrossProviders() {
        assertEquals(TokenCalibrationManager.FAMILY_QWEN,
                TokenCalibrationManager.familyForModel("qwen/qwen3.8-27b"));
        assertEquals(TokenCalibrationManager.FAMILY_QWEN,
                TokenCalibrationManager.familyForModel("Qwen3.5-27B-Derestricted"));
        assertEquals(TokenCalibrationManager.FAMILY_QWEN,
                TokenCalibrationManager.familyForModel("qwen-3-235b-a22b-instruct-2507"));
    }

    @Test
    void familyForModelGroupsGpt() {
        assertEquals(TokenCalibrationManager.FAMILY_GPT,
                TokenCalibrationManager.familyForModel("openai/gpt-oss-120b"));
        assertEquals(TokenCalibrationManager.FAMILY_GPT,
                TokenCalibrationManager.familyForModel("openai/gpt-oss-20b"));
        assertEquals(TokenCalibrationManager.FAMILY_GPT,
                TokenCalibrationManager.familyForModel("@cf/openai/gpt-oss-120b"));
    }

    @Test
    void familyForModelDefaultsForOtherAndNull() {
        assertEquals(TokenCalibrationManager.FAMILY_DEFAULT,
                TokenCalibrationManager.familyForModel("llama3.2:3b"));
        assertEquals(TokenCalibrationManager.FAMILY_DEFAULT,
                TokenCalibrationManager.familyForModel("mistral-large-latest"));
        assertEquals(TokenCalibrationManager.FAMILY_DEFAULT,
                TokenCalibrationManager.familyForModel(null));
    }

    @Test
    void groqIptmLimitIsLowerForQwen() {
        assertEquals(7000, AIService.groqIptmLimitForModel("qwen/qwen3.8-27b"));
        assertEquals(8000, AIService.groqIptmLimitForModel("openai/gpt-oss-120b"));
        assertEquals(8000, AIService.groqIptmLimitForModel("openai/gpt-oss-20b"));
        assertEquals(8000, AIService.groqIptmLimitForModel("something-else"));
    }

    @Test
    void trimTargetsLimitWithHeadroom() {
        // 7000*0.85/8000 = 0.74375 -> 74 of 100
        assertEquals(74, TokenCalibrationManager.computeTrimmedSize(100, 8000, 7000));
        // actual == limit -> 0.85 -> 85
        assertEquals(85, TokenCalibrationManager.computeTrimmedSize(100, 8000, 8000));
    }

    @Test
    void trimClampsExtremeRatiosAndSmallLists() {
        // 7000*0.85/20000 = 0.2975 -> clamped to 0.3 -> 30
        assertEquals(30, TokenCalibrationManager.computeTrimmedSize(100, 20000, 7000));
        // tiny lists never trim
        assertEquals(10, TokenCalibrationManager.computeTrimmedSize(10, 20000, 7000));
        assertEquals(5, TokenCalibrationManager.computeTrimmedSize(5, 20000, 7000));
        // unparseable errors -> no trim
        assertEquals(100, TokenCalibrationManager.computeTrimmedSize(100, null, 7000));
        assertEquals(100, TokenCalibrationManager.computeTrimmedSize(100, 8000, null));
    }

    @Test
    void rawEstimatePricesCharacterClassesNotRawLength() {
        String gpt = "openai/gpt-oss-120b";
        String qwen = "qwen/qwen3.8-27b";
        assertEquals(0, TokenCalibrationManager.estimateRaw(""));
        assertEquals(0, TokenCalibrationManager.estimateRaw(null, gpt));
        assertEquals(1, TokenCalibrationManager.estimateRaw("x", gpt));

        // Digits and emoji cost far more per char than prose; a flat
        // chars/token constant cannot express this.
        String digits = "1234567890";
        String prose = "aaaaaaaaaa";
        assertTrue(TokenCalibrationManager.estimateRaw(digits, gpt)
                > TokenCalibrationManager.estimateRaw(prose, gpt),
                "digits must cost more than prose letters");

        // Whitespace is nearly free (0.010 tok/char vs 0.216 for latin).
        assertEquals(1, TokenCalibrationManager.estimateRaw("     ", gpt));
        assertTrue(TokenCalibrationManager.estimateRaw(prose, gpt) > 2);

        // Emoji are astral: one glyph, two chars, 1-2 tokens. Must not double-count.
        assertEquals(2, TokenCalibrationManager.estimateRaw("🚀", gpt));
        assertEquals(1, TokenCalibrationManager.estimateRaw("🚀", qwen));

        // CJK is denser than latin.
        assertTrue(TokenCalibrationManager.estimateRaw("今天天气不错", gpt)
                > TokenCalibrationManager.estimateRaw("abcdefghij", gpt) / 2);

        // qwen charges ~1 token per digit vs gpt ~0.84, so identical text costs more.
        assertTrue(TokenCalibrationManager.estimateRaw(digits, qwen)
                > TokenCalibrationManager.estimateRaw(digits, gpt));

        // Log-line furniture (the dominant class in gathered history).
        assertEquals(12, TokenCalibrationManager.estimateRaw("[12:34:56] <@u:x> ", gpt));
    }

    @Test
    void estimateIsMonotonicInAppendedText() {
        String gpt = "openai/gpt-oss-120b";
        StringBuilder acc = new StringBuilder("[12:34:56] <@user:matrix.org> lgtm, merging ");
        int prev = 0;
        for (String extra : new String[]{"ok", " ", "🚀", "0xdeadbeef", "今天", "https://x.org/a?b=1"}) {
            acc.append(extra);
            int now = TokenCalibrationManager.estimateRaw(acc.toString(), gpt);
            assertTrue(now >= prev, "appending text must never lower the estimate: " + extra);
            prev = now;
        }
    }

    @Test
    void baseCharsPerTokenStaysInRealisticChatRange() {
        // Derived from the weighted estimator over log-line probes; recorded in
        // calibration history so rawBase stays comparable across estimator changes.
        double gptBase = TokenCalibrationManager.baseCharsPerToken("openai/gpt-oss-120b");
        double qwenBase = TokenCalibrationManager.baseCharsPerToken("qwen/qwen3.8-27b");
        assertTrue(gptBase > 2.0 && gptBase < 3.5, "gpt base out of range: " + gptBase);
        assertTrue(qwenBase > 2.0 && qwenBase < 3.5, "qwen base out of range: " + qwenBase);
        assertTrue(TokenCalibrationManager.baseCharsPerToken(null) > 2.0);
    }

    @Test
    void classCountsPartitionTheString() {
        String text = "[12:34:56] <@u:x> 🚀 今天 Привет abc 123";
        long[] counts = TokenCalibrationManager.countClasses(text, "openai/gpt-oss-120b");
        long total = 0;
        for (long c : counts) total += c;
        long astralPairs = text.length() - text.codePointCount(0, text.length());
        // Astral glyphs span two chars but count once, so total = length - surrogate pairs.
        assertEquals(text.length() - astralPairs, total, "class counts must account for every char");
        assertEquals(1, counts[TokenCalibrationManager.CLASS_EMOJI]);
        assertEquals(9, counts[TokenCalibrationManager.CLASS_DIGIT]);
        assertEquals(6, counts[TokenCalibrationManager.CLASS_SPACE]);
        assertEquals(2, counts[TokenCalibrationManager.CLASS_CJK]);
        assertEquals(6, counts[TokenCalibrationManager.CLASS_OTHER], "Cyrillic is other, not CJK");
    }

    @Test
    void tpmErrorExcludesOutputLimits() {
        String inputTpm = "Rate limit reached for model `qwen/qwen3.8-27b` on tokens per minute (TPM): Limit 7000, Used 5000, Requested 3000.";
        assertTrue(TokenCalibrationManager.isGroqTpmError(inputTpm));
        assertTrue(TokenCalibrationManager.isCalibrationError(inputTpm));
        assertEquals(3000, TokenCalibrationManager.extractGroqRequested(inputTpm));
        assertEquals(7000, TokenCalibrationManager.extractGroqLimit(inputTpm));

        String outputTpm = "Rate limit reached for model `qwen/qwen3.8-27b` on output tokens per minute (OTPM): Limit 1000, Used 900, Requested 200.";
        assertFalse(TokenCalibrationManager.isGroqTpmError(outputTpm));
        assertFalse(TokenCalibrationManager.isCalibrationError(outputTpm));
    }
}
