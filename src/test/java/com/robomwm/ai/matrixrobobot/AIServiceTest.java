package com.robomwm.ai.matrixrobobot;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIServiceTest {

    @Test
    void appendStatusLinePreservesEarlierFailures() {
        String firstUpdate = AIService.appendStatusLine("Groq failed", "OpenRouter failed");
        String secondUpdate = AIService.appendStatusLine(firstUpdate, "Gemini failed");

        assertEquals("Groq failed\nOpenRouter failed", firstUpdate);
        assertEquals("Groq failed\nOpenRouter failed\nGemini failed", secondUpdate);
    }

    @Test
    void paseoImageCollectionRequiresAHistoryBoundAndActualImages() {
        assertFalse(AIService.shouldCollectImagesForBoundedHistory(-1, -1, null, null));
        assertTrue(AIService.shouldCollectImagesForBoundedHistory(24, -1, null, null));
        assertTrue(AIService.shouldCollectImagesForBoundedHistory(-1, 25, null, null));
        assertTrue(AIService.shouldCollectImagesForBoundedHistory(-1, -1, "$start", null));
        assertTrue(AIService.shouldCollectImagesForUnreadSummary(AIService.Prompts.TLDR_PREFIX));
        assertFalse(AIService.shouldCollectImagesForUnreadSummary(AIService.Prompts.SUMMARY_PREFIX));
        assertFalse(AIService.shouldUsePaseoForImages(false, List.of("mxc://server/image")));
        assertFalse(AIService.shouldUsePaseoForImages(true, null));
        assertFalse(AIService.shouldUsePaseoForImages(true, List.of()));
        assertTrue(AIService.shouldUsePaseoForImages(true, List.of("mxc://server/image")));
    }

    @Test
    void qualityFilteringDropsImagesFromFilteredMessages() {
        RoomHistoryManager.ChatLogsResult history = new RoomHistoryManager.ChatLogsResult(
                List.of("[date] <@filtered:example.org> image", "[date] <@kept:example.org> text"),
                "$excluded", null,
                List.of("mxc://server/excluded", "mxc://server/kept"), List.of("a.png", "b.png"),
                List.of("$excluded", "$kept"), List.of("$excluded", "$kept"));

        RoomHistoryManager.ChatLogsResult filtered =
                AIService.filterHistoryUserMessages(history, "@filtered:example.org");

        assertEquals(List.of("$kept"), filtered.eventIds);
        assertEquals(List.of("mxc://server/kept"), filtered.imageUrls);
        assertEquals(List.of("$kept"), filtered.imageEventIds);
    }

    @Test
    void configIgnoresRemovedVisionSettings() throws Exception {
        MatrixRobobot.Config config = new ObjectMapper().readValue(
                "{\"imageCaptionBackend\":\"OLLAMA\",\"imageCaptionModel\":\"unused\"}",
                MatrixRobobot.Config.class);

        assertNotNull(config);
    }

    @Test
    void ollamaCloudIsLastInAutomaticFallbackOrder() {
        assertEquals(AIService.Backend.OLLAMA_CLOUD,
                AIService.AUTO_PROVIDER_ORDER.get(AIService.AUTO_PROVIDER_ORDER.size() - 1));
    }

    @Test
    void groqQwenDefaultsDisableThinking() {
        for (String model : new String[]{"qwen/qwen3-32b", "qwen/qwen3.6-27b", "qwen/qwen3.8-27b"}) {
            org.junit.jupiter.api.Assertions.assertTrue(AIService.isGroqQwenModel(model), model);
            Map<String, Object> payload = new HashMap<>();
            AIService.applyGroqQwenNonThinkingDefaults(payload);

            assertEquals("none", payload.get("reasoning_effort"));
            assertEquals(AIService.GROQ_QWEN_MAX_TOKENS, payload.get("max_tokens"));
            org.junit.jupiter.api.Assertions.assertFalse(payload.containsKey("chat_template_kwargs"));
            org.junit.jupiter.api.Assertions.assertFalse(payload.containsKey("reasoning_format"));
            org.junit.jupiter.api.Assertions.assertFalse(payload.containsKey("include_reasoning"));
        }
    }

    @Test
    void gatherBudgetFollowsFirstKnownLimitInChain() {
        AIService groqFirst = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, "groq", null, null, null, null,
                null, List.of("openai/gpt-oss-120b", "qwen/qwen3.8-27b"), null, null, null);
        assertEquals(8000, groqFirst.gatherBudgetForChain(AIService.Backend.AUTO, null));

        AIService qwenFirst = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, "groq", null, null, null, null,
                null, List.of("qwen/qwen3.8-27b", "openai/gpt-oss-120b"), null, null, null);
        assertEquals(7000, qwenFirst.gatherBudgetForChain(AIService.Backend.AUTO, null));

        AIService none = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, null, null, null, null, null,
                null, null, null, null, null);
        assertEquals(AIService.DEFAULT_GATHER_BUDGET,
                none.gatherBudgetForChain(AIService.Backend.AUTO, null));

        // forced model narrows the chain to one attempt
        assertEquals(7000, groqFirst.gatherBudgetForChain(AIService.Backend.GROQ, "qwen/qwen3.8-27b"));
    }

    @Test
    void gatherBudgetUsesKnownProviderLimits() {
        AIService cerebras = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                "csk", null, null, null, null, null,
                List.of("gpt-oss-120b"), null, null, null, null);
        assertEquals(AIService.CEREBRAS_INPUT_LIMIT,
                cerebras.gatherBudgetForChain(AIService.Backend.AUTO, null));

        // Gemini's known input budget is below the maximum supported context.
        AIService gemini = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, null, null, null, null, null,
                null, null, null, null, null,
                new AIService.ExtraProviders("g", null, null, null, null, null, null,
                        List.of("gemini-2.5-flash"), null, null, null, null, null));
        assertEquals(AIService.GEMINI_INPUT_LIMIT,
                gemini.gatherBudgetForChain(AIService.Backend.AUTO, null));
    }

    @Test
    void gatherBudgetUsesConfiguredOllamaModelContext() {
        for (String[] modelAndExpected : new String[][]{
                {"beam", "800000"},
                {"minimax-m3", "1000000"},
                {"nemotron-3-ultra", "256000"},
                {"gpt-oss:120b", "128000"},
                {"gemma4:31b", "256000"}}) {
            AIService ollama = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                    null, null, null, null, null, null,
                    null, null, null, null, null,
                    new AIService.ExtraProviders(null, null, null, null, null, "ollama", null,
                            null, null, null, null, List.of(modelAndExpected[0]), null));
            int modelContext = Integer.parseInt(modelAndExpected[1]);
            assertEquals(modelContext, AIService.ollamaCloudInputLimitForModel(modelAndExpected[0]));
            assertEquals(modelContext,
                    ollama.gatherBudgetForChain(AIService.Backend.AUTO, null), modelAndExpected[0]);
        }

        AIService beamFirst = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, null, null, null, null, null,
                null, null, null, null, null,
                new AIService.ExtraProviders(null, null, null, null, null, "ollama", null,
                        null, null, null, null, List.of("beam", "minimax-m3"), null));
        assertEquals(AIService.OLLAMA_BEAM_INPUT_LIMIT,
                beamFirst.gatherBudgetForChain(AIService.Backend.AUTO, null));
    }

    @Test
    void beamIsAlwaysFirstAmongOllamaModels() {
        List<String> ordered = AIService.orderOllamaModels(
                List.of("minimax-m3", "gemma4:31b", "beam", "gpt-oss:120b"),
                new java.util.Random(1));

        assertEquals("beam", ordered.get(0));
        org.junit.jupiter.api.Assertions.assertEquals(4, ordered.size());
        org.junit.jupiter.api.Assertions.assertTrue(ordered.containsAll(
                List.of("minimax-m3", "gemma4:31b", "gpt-oss:120b")));
    }

    @Test
    void gatherTimeoutScalesForLargerHistoryBudgets() {
        assertEquals(AIService.GATHER_TIMEOUT_MS,
                AIService.gatherTimeoutForBudget(AIService.DEFAULT_GATHER_BUDGET));
        assertEquals(AIService.GATHER_TIMEOUT_MS * 8,
                AIService.gatherTimeoutForBudget(AIService.OLLAMA_MINIMAX_M3_INPUT_LIMIT));
    }
}
