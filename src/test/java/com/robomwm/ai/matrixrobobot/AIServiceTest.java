package com.robomwm.ai.matrixrobobot;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIServiceTest {

    @Test
    void appendStatusLinePreservesEarlierFailures() {
        String firstUpdate = AIService.appendStatusLine("Groq failed", "OpenRouter failed");
        String secondUpdate = AIService.appendStatusLine(firstUpdate, "ArliAI failed");

        assertEquals("Groq failed\nOpenRouter failed", firstUpdate);
        assertEquals("Groq failed\nOpenRouter failed\nArliAI failed", secondUpdate);
    }

    @Test
    void ollamaCloudIsFirstInAutomaticFallbackOrder() {
        assertEquals(List.of(AIService.Backend.OLLAMA_CLOUD, AIService.Backend.GROQ),
                AIService.AUTO_PROVIDER_ORDER.subList(0, 2));
    }

    @Test
    void arliAiDefaultsDisableThinking() {
        Map<String, Object> payload = new HashMap<>();
        AIService.applyArliAiNonThinkingDefaults(payload);

        assertEquals(Map.of("enable_thinking", false), payload.get("chat_template_kwargs"));
        assertEquals("none", payload.get("reasoning_effort"));
        assertEquals(0, payload.get("thinking_token_budget"));
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
                null, null, "groq", null, null, null, null,
                null, null, List.of("openai/gpt-oss-120b", "qwen/qwen3.8-27b"), null, null, null);
        assertEquals(8000, groqFirst.gatherBudgetForChain(AIService.Backend.AUTO, null));

        AIService qwenFirst = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, null, "groq", null, null, null, null,
                null, null, List.of("qwen/qwen3.8-27b", "openai/gpt-oss-120b"), null, null, null);
        assertEquals(7000, qwenFirst.gatherBudgetForChain(AIService.Backend.AUTO, null));

        AIService arliOnly = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                "arli", null, null, null, null, null, null,
                List.of("Qwen3.5-27B-Derestricted"), null, null, null, null, null);
        assertEquals(AIService.ARLIAI_INPUT_LIMIT,
                arliOnly.gatherBudgetForChain(AIService.Backend.AUTO, null));

        AIService none = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertEquals(AIService.DEFAULT_GATHER_BUDGET,
                none.gatherBudgetForChain(AIService.Backend.AUTO, null));

        // forced model narrows the chain to one attempt
        assertEquals(7000, groqFirst.gatherBudgetForChain(AIService.Backend.GROQ, "qwen/qwen3.8-27b"));
    }

    @Test
    void gatherBudgetUsesKnownProviderLimits() {
        AIService cerebras = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, "csk", null, null, null, null, null,
                null, List.of("gpt-oss-120b"), null, null, null, null);
        assertEquals(AIService.CEREBRAS_INPUT_LIMIT,
                cerebras.gatherBudgetForChain(AIService.Backend.AUTO, null));

        // Gemini's known input budget is below the maximum supported context.
        AIService gemini = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
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
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    new AIService.ExtraProviders(null, null, null, null, null, "ollama", null,
                            null, null, null, null, List.of(modelAndExpected[0]), null));
            int modelContext = Integer.parseInt(modelAndExpected[1]);
            assertEquals(modelContext, AIService.ollamaCloudInputLimitForModel(modelAndExpected[0]));
            assertEquals(modelContext,
                    ollama.gatherBudgetForChain(AIService.Backend.AUTO, null), modelAndExpected[0]);
        }

        AIService beamFirst = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
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
