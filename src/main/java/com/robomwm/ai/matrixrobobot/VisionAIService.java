package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class VisionAIService extends AIService {
    private final ImageFetcher imageFetcher;

    public VisionAIService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken,
                           String arliApiKey, String cerebrasApiKey, ImageFetcher imageFetcher) {
        super(client, mapper, homeserver, accessToken, arliApiKey, cerebrasApiKey);
        this.imageFetcher = imageFetcher;
    }

    public enum Backend {
        AUTO, ARLIAI
    }

    /**
     * Query AI with vision support - fetches images and includes them in the prompt.
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
            final long lastProgressUpdate = System.currentTimeMillis();
            RoomHistoryManager.ProgressCallback progressCallback = (msgCount, estTokens) -> {
                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate >= 5000) {
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

            performVisionAIQuery(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag, preferredBackend, statusEventId, 900);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying vision AI: " + e.getMessage());
        }
    }

    private void performVisionAIQuery(String responseRoomId, String exportRoomId, RoomHistoryManager.ChatLogsResult history,
                                     String question, String promptPrefix, AtomicBoolean abortFlag,
                                     Backend preferredBackend, String statusEventId, int timeoutSeconds) {
        if (abortFlag != null && abortFlag.get()) return;

        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);

        try {
            String questionPart = (question != null && !question.isEmpty()) ? " and prompt: " + question : "";
            String backendName = "Arli AI (Vision)";
            String queryDescription = history.logs.size() + " messages";
            if (history.imageUrls != null && !history.imageUrls.isEmpty()) {
                queryDescription += " + " + history.imageUrls.size() + " images";
            }
            String queryStatusMsg = "\u23F3 Querying " + backendName + " with " + queryDescription + questionPart;

            // Update status
            if (statusEventId != null) {
                matrixClient.updateTextMessage(responseRoomId, statusEventId, queryStatusMsg);
            } else {
                statusEventId = matrixClient.sendTextWithEventId(responseRoomId, queryStatusMsg);
            }

            if (abortFlag != null && abortFlag.get()) return;

            // Fetch and encode images
            List<String> base64Images = new ArrayList<>();
            if (history.imageUrls != null && !history.imageUrls.isEmpty()) {
                System.out.println("Starting to fetch " + history.imageUrls.size() + " images for vision summary");
                matrixClient.updateTextMessage(responseRoomId, statusEventId, "Fetching " + history.imageUrls.size() + " images...");
                base64Images = imageFetcher.fetchAndEncodeImages(history.imageUrls);
            }

            // Build prompt
            String prompt = buildPrompt(question, history.logs, promptPrefix);

            // Build content with text and images
            List<Map<String, Object>> content = VisionPromptBuilder.buildVisionContent(prompt, base64Images);

            // Call vision AI
            callVisionArliAI(content, responseRoomId, exportRoomId, history.firstEventId, abortFlag, statusEventId, timeoutSeconds);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error performing vision AI query: " + e.getMessage());
        }
    }

    private void callVisionArliAI(List<Map<String, Object>> content, String responseRoomId, String exportRoomId,
                                 String firstEventId, AtomicBoolean abortFlag, String statusEventId, int timeoutSeconds) throws Exception {
        String arliApiUrl = "https://api.arliai.com";
        if (arliApiKey == null || arliApiKey.isEmpty()) {
            throw new Exception("ARLI_API_KEY is not configured.");
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", AIService.Prompts.SYSTEM_OVERVIEW));
        messages.add(Map.of("role", "user", "content", content));

        Map<String, Object> arliPayload = Map.of(
                "model", "Qwen3.5-27B-Derestricted", // Vision-capable model
                "messages", messages,
                "stream", true,
                "output_kind", "delta"
        );
        String jsonPayload = mapper.writeValueAsString(arliPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(arliApiUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + arliApiKey)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        streamArliAIResponse(request, responseRoomId, exportRoomId, firstEventId, "Vision ArliAI", abortFlag);
    }
}