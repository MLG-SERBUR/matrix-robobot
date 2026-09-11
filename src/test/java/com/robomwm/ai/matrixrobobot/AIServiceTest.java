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
            assertEquals(Map.of("enable_thinking", false), payload.get("chat_template_kwargs"));
            org.junit.jupiter.api.Assertions.assertFalse(payload.containsKey("reasoning_format"));
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
        assertEquals(AIService.MAX_GATHER_BUDGET,
                none.gatherBudgetForChain(AIService.Backend.AUTO, null));

        // forced model narrows the chain to one attempt
        assertEquals(7000, groqFirst.gatherBudgetForChain(AIService.Backend.GROQ, "qwen/qwen3.8-27b"));
    }

    @Test
    void gatherBudgetUsesFreeTierLimitsCappedAtFetchCeiling() {
        AIService cerebras = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, "csk", null, null, null, null, null,
                null, List.of("gpt-oss-120b"), null, null, null, null);
        assertEquals(AIService.CEREBRAS_INPUT_LIMIT,
                cerebras.gatherBudgetForChain(AIService.Backend.AUTO, null));

        // 250K Gemini budget is unfetchable in the 15s gather window: capped.
        AIService gemini = new AIService(HttpClient.newHttpClient(), new ObjectMapper(), "https://x", "t",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                new AIService.ExtraProviders("g", null, null, null, null, null, null,
                        List.of("gemini-2.5-flash"), null, null, null, null, null));
        assertEquals(AIService.MAX_GATHER_BUDGET,
                gemini.gatherBudgetForChain(AIService.Backend.AUTO, null));
    }
}
