package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Vision-enhanced AI service that uses the Ollama proxy for image description.
 */
public class OllamaVisionAIService extends VisionAIService {
    private final String model;

    public OllamaVisionAIService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken,
                                 String arliApiKey, String groqApiKey, String openrouterApiKey, String freeLlmApiKey,
                                 String ollamaProxyApiKey, String ollamaProxyUrl,
                                 ImageFetcher imageFetcher, String model,
                                 List<String> arliModels, List<String> cerebrasModels, List<String> groqModels, List<String> openrouterModels, 
                                 List<String> freeLlmModels, List<String> ollamaProxyModels) {
        super(client, mapper, homeserver, accessToken, arliApiKey, groqApiKey, openrouterApiKey, freeLlmApiKey,
              ollamaProxyApiKey, ollamaProxyUrl, imageFetcher,
              arliModels, cerebrasModels, groqModels, openrouterModels, freeLlmModels, ollamaProxyModels);
        this.model = model != null ? model : (ollamaProxyModels != null && !ollamaProxyModels.isEmpty() ? ollamaProxyModels.get(0) : "llava");
    }

    @Override
    protected String describeImage(String mxcUrl) throws Exception {
        return AIRequestQueue.run("Ollama vision image description", () -> describeImageUnqueued(mxcUrl));
    }

    private String describeImageUnqueued(String mxcUrl) throws Exception {
        // Fetch and encode single image
        System.out.println("Fetching image from Matrix for Ollama: " + mxcUrl);
        List<String> encoded = imageFetcher.fetchAndEncodeImages(List.of(mxcUrl));
        if (encoded.isEmpty()) {
            System.out.println("Failed to fetch image from Matrix: " + mxcUrl);
            return null;
        }

        String base64Image = encoded.get(0);
        // Ollama expects raw base64, not data URL. Strip prefix if present.
        if (base64Image.startsWith("data:")) {
            int commaIdx = base64Image.indexOf(",");
            if (commaIdx != -1) {
                base64Image = base64Image.substring(commaIdx + 1);
            }
        }
        
        // Ollama native /api/chat format for vision
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", "Briefly describe the input image in 1-2 sentences. Be terse, incomplete sentence ok.");
        message.put("images", List.of(base64Image));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(message));
        payload.put("stream", false);
        
        String jsonPayload = mapper.writeValueAsString(payload);

        System.out.println("Sending image to Ollama proxy vision API: " + mxcUrl + " using model " + model);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaProxyUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ollamaProxyApiKey)
                .timeout(Duration.ofSeconds(AIService.AI_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String errorBody = response.body();
            System.err.println("Ollama vision API error " + response.statusCode() + ": " + errorBody);
            throw new Exception("Ollama vision API error " + response.statusCode() + ": " + errorBody);
        }

        JsonNode root = mapper.readTree(response.body());
        
        // Handle Ollama native response format: {"message": {"content": "..."}}
        if (root.has("message")) {
            String text = root.get("message").get("content").asText(null);
            if (text != null) {
                text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
            }
            return text;
        }
        
        // Fallback to OpenAI format if the proxy translates it
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String text = choices.get(0).path("message").path("content").asText(null);
            if (text != null) {
                text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
            }
            return text;
        }

        System.out.println("No content in Ollama vision response for: " + mxcUrl);
        return null;
    }
}
