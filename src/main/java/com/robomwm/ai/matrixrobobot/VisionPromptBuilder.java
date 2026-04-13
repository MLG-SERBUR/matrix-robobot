package com.robomwm.ai.matrixrobobot;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Builds prompts with text and images for vision-enabled AI queries.
 */
public class VisionPromptBuilder {

    /**
     * Build content array for vision AI request.
     * Content includes the text prompt first, then images.
     */
    public static List<Map<String, Object>> buildVisionContent(String textPrompt, List<String> base64Images) {
        List<Map<String, Object>> content = new ArrayList<>();

        // Add text prompt
        content.add(Map.of(
            "type", "text",
            "text", textPrompt
        ));

        // Add images
        for (String base64Image : base64Images) {
            content.add(Map.of(
                "type", "image_url",
                "image_url", Map.of(
                    "url", base64Image
                )
            ));
        }

        return content;
    }

    /**
     * Build content array for text-only request (fallback).
     */
    public static List<Map<String, Object>> buildTextContent(String textPrompt) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of(
            "type", "text",
            "text", textPrompt
        ));
        return content;
    }
}