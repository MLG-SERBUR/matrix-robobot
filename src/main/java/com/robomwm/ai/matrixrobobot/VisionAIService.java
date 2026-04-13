package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vision-enhanced AI service. Pre-describes images individually via vision API calls,
 * injects text descriptions into chat logs, then delegates to parent's text-only summary.
 * This avoids blowing out context limits with base64 image data.
 */
public class VisionAIService extends AIService {
    private final ImageFetcher imageFetcher;

    public VisionAIService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken,
                           String arliApiKey, String cerebrasApiKey, ImageFetcher imageFetcher) {
        // Pass null for cerebrasApiKey to ensure Vision AI never falls back to Cerebras
        super(client, mapper, homeserver, accessToken, arliApiKey, null);
        this.imageFetcher = imageFetcher;
    }

    public enum Backend {
        AUTO, ARLIAI
    }

    /**
     * Query AI with vision support.
     * Phase 1: Describe each image individually via vision API (sequential, non-streaming).
     * Phase 2: Inject descriptions into chat logs, run normal text-only summary.
     */
    public void queryAI(String responseRoomId, String exportRoomId, int hours, String fromToken, String question,
            String startEventId, boolean forward, ZoneId zoneId, int maxMessages, String promptPrefix,
            AtomicBoolean abortFlag, Backend preferredBackend) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);

        try {
            final String timeInfo;
            if (startEventId != null) {
                timeInfo = (forward ? "after " : "before ") + "message " + startEventId + " (limit "
                        + (maxMessages > 0 ? maxMessages + " messages" : hours + "h") + ")";
            } else if (maxMessages > 0) {
                timeInfo = "last " + maxMessages + " messages";
            } else {
                timeInfo = "last " + (hours > 0 ? hours + "h" : "all history");
            }

            // Send immediate status message
            String gatherMsg = "\uD83D\uDCE8 Gathering " + timeInfo + " with images...";
            String statusEventId = matrixClient.sendTextWithEventId(responseRoomId, gatherMsg);

            // Create progress callback
            final String fStatusEventId = statusEventId;
            final AtomicLong lastProgressUpdate = new AtomicLong(System.currentTimeMillis());
            RoomHistoryManager.ProgressCallback progressCallback = (msgCount, estTokens) -> {
                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate.get() >= 5000) {
                    lastProgressUpdate.set(now);
                    String tokenStr = estTokens >= 1000 ? String.format("%.1fk", estTokens / 1000.0) : String.valueOf(estTokens);
                    matrixClient.updateTextMessage(responseRoomId, fStatusEventId,
                            "\uD83D\uDCE8 Gathering " + timeInfo + " with images... (" + msgCount + " gathered, ~" + tokenStr + " tokens)");
                }
            };

            // Fetch history with images
            RoomHistoryManager.ChatLogsResult history = historyManager.fetchRoomHistoryRelative(
                exportRoomId, hours, fromToken, startEventId, forward, zoneId, maxMessages, true, abortFlag, progressCallback);

            if (history.errorMessage != null) {
                matrixClient.updateTextMessage(responseRoomId, statusEventId, history.errorMessage);
                return;
            }
            if (history.logs.isEmpty()) {
                matrixClient.updateTextMessage(responseRoomId, statusEventId,
                        "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            // Phase 1: Describe each image individually via vision API (sequential)
            if (history.imageUrls != null && !history.imageUrls.isEmpty()) {
                int imageCount = history.imageUrls.size();
                matrixClient.updateTextMessage(responseRoomId, statusEventId,
                        "\uD83D\uDDBC\uFE0F Describing " + imageCount + " image(s)...");

                List<String> imageDescriptions = new ArrayList<>();
                for (int i = 0; i < imageCount; i++) {
                    if (abortFlag != null && abortFlag.get()) return;

                    String imageUrl = history.imageUrls.get(i);
                    String caption = (history.imageCaptions != null && i < history.imageCaptions.size())
                            ? history.imageCaptions.get(i) : "image";

                    matrixClient.updateTextMessage(responseRoomId, statusEventId,
                            "\uD83D\uDDBC\uFE0F Describing image " + (i + 1) + "/" + imageCount + ": " + caption);

                    String description = describeImage(imageUrl, caption);
                    if (description != null && !description.isEmpty()) {
                        imageDescriptions.add("[\uD83D\uDDBC\uFE0F Image: " + caption + " \u2014 " + description + "]");
                    } else {
                        imageDescriptions.add("[\uD83D\uDDBC\uFE0F Image: " + caption + " \u2014 (could not describe)]");
                    }
                }

                // Inject image descriptions at end of chat logs
                history.logs.addAll(imageDescriptions);
                System.out.println("Injected " + imageDescriptions.size() + " image descriptions into chat logs");
            }

            // Phase 2: Run normal text-only summary with enriched logs
            // Delegate to parent's performAIQuery which handles context limits, Cerebras fallback, etc.
            performAIQuery(responseRoomId, exportRoomId, history, question, promptPrefix,
                    abortFlag, AIService.Backend.AUTO, null, 900, statusEventId);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying vision AI: " + e.getMessage());
        }
    }

    /**
     * Describe a single image via ArliAI vision API. Sequential, non-streaming.
     * Returns text description or null on failure.
     */
    private String describeImage(String mxcUrl, String caption) {
        try {
            // Fetch and encode single image
            List<String> encoded = imageFetcher.fetchAndEncodeImages(List.of(mxcUrl));
            if (encoded.isEmpty()) {
                System.out.println("Failed to fetch image: " + mxcUrl);
                return null;
            }

            String base64Image = encoded.get(0);

            // Build vision content: text prompt + single image
            String prompt = "Briefly describe this image in 1-2 sentences. Context: it was shared in a chat with caption '" + caption + "'.";
            List<Map<String, Object>> content = VisionPromptBuilder.buildVisionContent(prompt, List.of(base64Image));

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "You describe images concisely."));
            messages.add(Map.of("role", "user", "content", content));

            Map<String, Object> payload = Map.of(
                    "model", "Qwen3.5-27B-Derestricted",
                    "messages", messages,
                    "stream", false,
                    "max_completion_tokens", 200
            );
            String jsonPayload = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.arliai.com/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + arliApiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Image description API error " + response.statusCode() + ": " + response.body());
                return null;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String text = choices.get(0).path("message").path("content").asText(null);
                // Strip thinking tags if present (Qwen reasoning models)
                if (text != null) {
                    text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
                }
                System.out.println("Described image " + caption + ": "
                        + (text != null ? text.substring(0, Math.min(100, text.length())) : "null"));
                return text;
            }

            return null;
        } catch (Exception e) {
            System.err.println("Error describing image " + mxcUrl + ": " + e.getMessage());
            return null;
        }
    }
}