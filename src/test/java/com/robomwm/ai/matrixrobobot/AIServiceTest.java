package com.robomwm.ai.matrixrobobot;

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
}
