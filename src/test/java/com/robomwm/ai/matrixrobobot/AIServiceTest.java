package com.robomwm.ai.matrixrobobot;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
}
