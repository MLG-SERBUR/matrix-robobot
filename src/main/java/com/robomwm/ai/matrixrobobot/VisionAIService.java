package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Vision-enhanced AI service. Pre-describes images individually via vision API calls,
 * injects text descriptions into chat logs, then delegates to parent's text-only summary.
 * This avoids blowing out context limits with base64 image data.
 *
 * Image descriptions are cached to a JSON file keyed by mxc:// URL to avoid redundant API calls.
 */
public class VisionAIService extends AIService {
    private final ImageFetcher imageFetcher;
    private static final String DESCRIPTION_CACHE_FILE = "image_description_cache.json";

    public VisionAIService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken,
                           String arliApiKey, String groqApiKey, String openrouterApiKey, ImageFetcher imageFetcher) {
        // Pass null for cerebrasApiKey to ensure Vision AI never falls back to Cerebras
        super(client, mapper, homeserver, accessToken, arliApiKey, null, groqApiKey, openrouterApiKey);
        this.imageFetcher = imageFetcher;
    }

    @Override
    protected RoomHistoryManager.ChatLogsResult fetchHistoryForQuery(String exportRoomId, int hours, String fromToken,
            String startEventId, boolean forward, java.time.ZoneId zoneId, int maxMessages,
            java.util.concurrent.atomic.AtomicBoolean abortFlag, RoomHistoryManager.ProgressCallback progressCallback) {
        return historyManager.fetchRoomHistoryRelative(
                exportRoomId, hours, fromToken, startEventId, forward, zoneId, maxMessages, true, true, abortFlag,
                progressCallback);
    }

    @Override
    protected RoomHistoryManager.ChatLogsResult prepareHistoryForQuery(String responseRoomId, String exportRoomId,
            RoomHistoryManager.ChatLogsResult history, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            String statusEventId) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);

        if (history.imageUrls == null || history.imageUrls.isEmpty()) {
            return history;
        }

        int imageCount = history.imageUrls.size();
        matrixClient.updateNoticeMessage(responseRoomId, statusEventId,
                "\uD83D\uDDBC\uFE0F Describing " + imageCount + " image(s)...");

        ObjectNode cache = loadDescriptionCache();
        int cachedCount = 0;
        List<String> imageDescriptions = new ArrayList<>();

        for (int i = 0; i < imageCount; i++) {
            if (abortFlag != null && abortFlag.get()) return history;

            String imageUrl = history.imageUrls.get(i);
            String caption = (history.imageCaptions != null && i < history.imageCaptions.size())
                    ? history.imageCaptions.get(i) : "image";

            String cachedDescription = cache.has(imageUrl) ? cache.get(imageUrl).asText(null) : null;
            if (cachedDescription != null && !cachedDescription.isEmpty()) {
                System.out.println("Cache hit for image " + (i + 1) + "/" + imageCount + ": " + imageUrl);
                imageDescriptions.add("[\uD83D\uDDBC\uFE0F Image: " + caption + " \u2014 " + cachedDescription + "]");
                cachedCount++;
                continue;
            }

            matrixClient.updateNoticeMessage(responseRoomId, statusEventId,
                    "\uD83D\uDDBC\uFE0F Describing image " + (i + 1) + "/" + imageCount
                    + " (" + cachedCount + " cached): " + caption);

            String description;
            try {
                description = describeImage(imageUrl, caption);
            } catch (Exception e) {
                throw new RuntimeException("Error describing image '" + caption + "': " + e.getMessage(), e);
            }
            if (description != null && !description.isEmpty()) {
                imageDescriptions.add("[\uD83D\uDDBC\uFE0F Image: " + caption + " \u2014 " + description + "]");
                cache.put(imageUrl, description);
            } else {
                imageDescriptions.add("[\uD83D\uDDBC\uFE0F Image: " + caption + " \u2014 (could not describe)]");
            }
        }

        saveDescriptionCache(cache);
        history.logs.addAll(imageDescriptions);
        System.out.println("Injected " + imageDescriptions.size() + " image descriptions into chat logs"
                + " (" + cachedCount + " from cache, " + (imageDescriptions.size() - cachedCount) + " newly described)");
        return history;
    }

    /**
     * Describe a single image via ArliAI vision API. Sequential, non-streaming.
     * Returns text description or null on non-fatal failure.
     * Throws Exception on fatal API errors (403, rate limit, etc.) to abort the entire operation.
     */
    private String describeImage(String mxcUrl, String caption) throws Exception {
        // Fetch and encode single image
        System.out.println("Fetching image from Matrix: " + mxcUrl);
        List<String> encoded = imageFetcher.fetchAndEncodeImages(List.of(mxcUrl));
        if (encoded.isEmpty()) {
            System.out.println("Failed to fetch image from Matrix: " + mxcUrl);
            return null;
        }

        String base64Image = encoded.get(0);
        int base64Len = base64Image.length();
        System.out.println("Image fetched and encoded: " + mxcUrl + " (" + (base64Len / 1024) + "KB base64)");

        // Build vision content: text prompt + single image
        String prompt = "Briefly describe this image in 1-2 sentences. Context: it was shared in a chat with caption '" + caption + "'.";
        List<Map<String, Object>> content = VisionPromptBuilder.buildVisionContent(prompt, List.of(base64Image));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "You are a vision assistant. Do not reason. Do not think. Do not draft. Output only the final terse one sentence (incomplete sentence ok) caption immediately."));
        messages.add(Map.of("role", "user", "content", content));

        Map<String, Object> payload = Map.of(
                "model", "Qwen3.5-27B-Derestricted",
                "messages", messages,
                "stream", false
        );
        String jsonPayload = mapper.writeValueAsString(payload);

        System.out.println("Sending image to ArliAI vision API: " + mxcUrl + " (caption: " + caption + ")");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.arliai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + arliApiKey)
                .timeout(Duration.ofSeconds(AIService.AI_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String errorBody = response.body();
            System.err.println("Image description API error " + response.statusCode() + ": " + errorBody);
            // Fatal: throw to abort entire vision operation
            throw new Exception("ArliAI vision API error " + response.statusCode() + ": " + errorBody);
        }

        System.out.println("ArliAI vision API responded 200 for: " + mxcUrl);
        System.out.println("Response body: " + response.body());

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

        System.out.println("No choices in ArliAI response for: " + mxcUrl);
        return null;
    }

    // --- Description Cache ---

    private ObjectNode loadDescriptionCache() {
        File cacheFile = new File(DESCRIPTION_CACHE_FILE);
        if (cacheFile.exists()) {
            try {
                JsonNode node = mapper.readTree(cacheFile);
                if (node.isObject()) {
                    System.out.println("Loaded image description cache: " + node.size() + " entries");
                    return (ObjectNode) node;
                }
            } catch (IOException e) {
                System.err.println("Failed to load description cache, starting fresh: " + e.getMessage());
            }
        }
        return mapper.createObjectNode();
    }

    private void saveDescriptionCache(ObjectNode cache) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(DESCRIPTION_CACHE_FILE), cache);
            System.out.println("Saved image description cache: " + cache.size() + " entries");
        } catch (IOException e) {
            System.err.println("Failed to save description cache: " + e.getMessage());
        }
    }
}
