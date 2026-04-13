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

/**
 * AI service for vision-enabled commands that include images.
 */
public class VisionAIService {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String homeserver;
    private final String accessToken;
    private final String arliApiKey;
    private final RoomHistoryManager historyManager;
    private final ImageFetcher imageFetcher;

    public VisionAIService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken,
                          String arliApiKey, RoomHistoryManager historyManager, ImageFetcher imageFetcher) {
        this.client = client;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.arliApiKey = arliApiKey;
        this.historyManager = historyManager;
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

        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        String[] eventIdHolder = new String[]{null};

        StringBuilder reasoning = new StringBuilder();
        StringBuilder responseContent = new StringBuilder();
        long lastUpdate = System.currentTimeMillis();
        final long startTime = System.currentTimeMillis();

        int updateCount = 0;
        String[] clockFaces = {"🕛", "🕧", "🕐", "🕜", "🕑", "🕝", "🕒", "🕞", "🕓", "🕟", "🕔", "🕠", "🕕", "🕡", "🕖", "🕢", "🕗", "🕣", "🕘", "🕤", "🕙", "🕥", "🕚", "🕦"};

        try {
            System.out.println("Starting Vision ArliAI streaming request...");
            HttpResponse<java.util.stream.Stream<String>> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofLines());

            if (httpResponse.statusCode() != 200) {
                String errorBody = httpResponse.body().collect(java.util.stream.Collectors.joining("\n"));
                throw new Exception("Status: " + httpResponse.statusCode() + " Body: " + errorBody);
            }

            try (java.util.stream.Stream<String> lines = httpResponse.body()) {
                java.util.Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    if (abortFlag != null && abortFlag.get()) {
                        System.out.println("Vision ArliAI streaming aborted by flag.");
                        break;
                    }
                    String line = it.next();
                    String data = line.trim();
                    if (data.isEmpty()) continue;

                    if (data.startsWith("data:") && !data.contains("[DONE]")) {
                        try {
                            String json = data.substring(5).trim();
                            if (json.isEmpty()) continue;

                            JsonNode node = mapper.readTree(json);
                            JsonNode choices = node.path("choices");
                            if (choices.isArray() && choices.size() > 0) {
                                JsonNode delta = choices.get(0).path("delta");
                                if (delta.has("content")) {
                                    responseContent.append(delta.get("content").asText());
                                } else if (delta.has("reasoning")) {
                                    reasoning.append(delta.get("reasoning").asText());
                                } else if (delta.has("reasoning_content")) {
                                    reasoning.append(delta.get("reasoning_content").asText());
                                }

                                long now = System.currentTimeMillis();
                                if ((responseContent.length() > 0 || reasoning.length() > 0) && now - lastUpdate > 10000) {
                                    lastUpdate = now;
                                    StringBuilder streamingOutput = new StringBuilder();
                                    if (reasoning.length() > 0) {
                                        String r = trimReasoning(reasoning.toString());
                                        streamingOutput.append("> ").append(r.replace("\n", "\n> ")).append("\n\n");
                                    }
                                    if (responseContent.length() > 0) {
                                        streamingOutput.append(responseContent.toString());
                                    }

                                    String output = streamingOutput.toString();
                                    if (output.length() > 16000) {
                                        output = output.substring(0, 15900) + "... [TRUNCATED]";
                                    }

                                    // Append elapsed thinking time to clock emoji (e.g. 🕒 1m12s)
                                    long elapsedMs = now - startTime;
                                    long elapsedSec = elapsedMs / 1000;
                                    String elapsedStr = elapsedSec < 60 ? (elapsedSec + "s") : ((elapsedSec / 60) + "m" + (elapsedSec % 60) + "s");
                                    String indicator = clockFaces[updateCount++ % clockFaces.length] + " " + elapsedStr;

                                    if (eventIdHolder[0] == null) {
                                        eventIdHolder[0] = matrixClient.sendMarkdownWithEventId(responseRoomId, output + " " + indicator);
                                    } else {
                                        matrixClient.updateMarkdownMessage(responseRoomId, eventIdHolder[0], output + " " + indicator);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Vision ArliAI Stream Parse Error: " + e.getMessage() + " | Line: " + line);
                        }
                    } else if (data.contains("[DONE]")) {
                        System.out.println("Vision ArliAI streaming finished normally ([DONE] received).");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error during Vision ArliAI streaming call: " + e.getMessage());
            e.printStackTrace();
            throw new Exception("Error during vision ArliAI streaming: " + e.getMessage(), e);
        }

        if (responseContent.length() == 0 && reasoning.length() == 0) {
            throw new Exception("No response received from vision ArliAI.");
        }

        System.out.println("Vision ArliAI Final State - Content size: " + responseContent.length() + ", Reasoning size: " + reasoning.length());

        String finalOutput;
        if (responseContent.toString().trim().isEmpty()) {
            if (reasoning.length() > 0) {
                System.out.println("Vision ArliAI: Content is empty, falling back to trimmed reasoning.");
                String trimmed = trimReasoning(reasoning.toString());
                finalOutput = "> " + trimmed.replace("\n", "\n> ") + "\n\n**Vision ArliAI: No final response was generated.**";
            } else {
                finalOutput = "**Vision ArliAI Error: No final response was generated.**";
            }
        } else {
            finalOutput = responseContent.toString();
        }

        if (finalOutput.length() > 16000) {
            finalOutput = finalOutput.substring(0, 15900) + "... [TRUNCATED]";
        }

        String answer = finalOutput + "\n\n" + (firstEventId != null ? "https://matrix.to/#/" + exportRoomId + "/" + firstEventId : "");
        if (eventIdHolder[0] == null) {
            matrixClient.sendMarkdownWithEventId(responseRoomId, answer);
        } else {
            matrixClient.updateMarkdownMessage(responseRoomId, eventIdHolder[0], answer);
        }
    }

    private String buildPrompt(String question, List<String> logs, String promptPrefix) {
        String logsStr = String.join("\n", logs);
        if (question != null && !question.isEmpty()) {
            if (AIService.Prompts.DEBUGAI_PREFIX.equals(promptPrefix)) {
                return question + "\n\n" + logsStr;
            }
            return AIService.Prompts.QUESTION_PREFIX + question + AIService.Prompts.QUESTION_SUFFIX + logsStr;
        } else {
            return promptPrefix + logsStr;
        }
    }

    private String trimReasoning(String r) {
        if (r == null || r.isEmpty()) return "";
        String[] lines = r.split("\n");
        List<Integer> stepIndices = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches("^(\\d+\\.|\\*).*")) {
                stepIndices.add(i);
            }
        }

        int lastStepIdx = -1;
        if (!stepIndices.isEmpty()) {
            lastStepIdx = stepIndices.get(stepIndices.size() - 1);
        }

        int maxLines = 15;
        int maxChars = 2500;

        StringBuilder sb = new StringBuilder();
        int lineCount = 0;
        for (int i = lines.length - 1; i >= 0 && lineCount < maxLines; i--) {
            if (sb.length() + lines[i].length() + 1 > maxChars) break;

            sb.insert(0, lines[i] + "\n");
            lineCount++;

            if (i == lastStepIdx) break;
        }

        return sb.toString().trim();
    }
}