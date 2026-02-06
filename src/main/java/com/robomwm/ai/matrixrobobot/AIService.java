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
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class AIService {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String homeserver;
    private final String accessToken;
    private final String arliApiKey;
    private final String cerebrasApiKey;
    private final RoomHistoryManager historyManager;

    public AIService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken, String arliApiKey,
            String cerebrasApiKey) {
        this.client = client;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.arliApiKey = arliApiKey;
        this.cerebrasApiKey = cerebrasApiKey;
        this.historyManager = new RoomHistoryManager(client, mapper, homeserver, accessToken);
    }

    public static class Prompts {
        public static final String SYSTEM_OVERVIEW = "You provide high level overview of a chat log.";
        public static final String QUESTION_PREFIX = "'";
        public static final String QUESTION_SUFFIX = "' Answer this prompt using these chat logs:\n\n";
        public static final String OVERVIEW_PREFIX = "Give a high level overview of the following chat logs. Use only a title and timestamp for each topic and only include one or more chat messages verbatim (with username) as bullet points for each topic; bias to include discovered solutions or interesting resources. Don't use table format. Then summarize with bullet points all of the chat at end, including discovered solutions or interesting resources; no complete sentences required, this should be brief:\n\n";
        public static final String TLDR_PREFIX = "Provide a very concise summary of the following chat logs that can be read in 15 seconds or less. Make use of bullet points of key topics with timestamp; be extremely brief, no need for complete sentences. Always include topics that are informative towards a discovered solution or resources; if the other topics are significantly discussed, these topics can be added on to increase reading time to no more than 30 seconds. Then directly include the best chat message verbatim; have bias towards one that is informative towards a discovered solution or informative resource:\n\n";
    }

    public void queryArliAI(String responseRoomId, String exportRoomId, int hours, String fromToken, String question,
            long startTimestamp, ZoneId zoneId, int maxMessages, String promptPrefix) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            String timeInfo = "";
            if (startTimestamp > 0) {
                String dateStr = java.time.Instant.ofEpochMilli(startTimestamp)
                        .atZone(zoneId)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                timeInfo = "starting at " + dateStr + " (next "
                        + (maxMessages > 0 ? maxMessages + " messages" : hours + "h") + ")";
            } else {
                if (maxMessages > 0) {
                    timeInfo = "last " + maxMessages + " messages";
                } else {
                    timeInfo = "last " + (hours > 0 ? hours + "h" : "all history");
                }
            }
            matrixClient.sendMarkdown(responseRoomId, "Querying Arli AI with chat logs from " + exportRoomId + " ("
                    + timeInfo + (question != null ? " and question: " + question : "") + ")...");

            long endTime = startTimestamp > 0 ? startTimestamp + (long) hours * 3600L * 1000L : -1;

            RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryDetailed(exportRoomId, hours,
                    fromToken, startTimestamp, endTime, zoneId, maxMessages);
            if (result.logs.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId,
                        "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            String prompt = buildPrompt(question, result.logs, promptPrefix);

            String arliApiUrl = "https://api.arliai.com";

            if (arliApiKey == null || arliApiKey.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId, "ARLI_API_KEY is not configured.");
                return;
            }

            List<Map<String, String>> messages = buildMessages(prompt);

            Map<String, Object> arliPayload = Map.of(
                    "model", "Gemma-3-27B-it",
                    "messages", messages,
                    "stream", false);
            String jsonPayload = mapper.writeValueAsString(arliPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(arliApiUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + arliApiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode arliResponse = mapper.readTree(response.body());
                String arliAnswer = arliResponse.path("choices").get(0).path("message").path("content")
                        .asText("No response from Arli AI.");

                arliAnswer = appendMessageLink(arliAnswer, exportRoomId, result.firstEventId);

                matrixClient.sendMarkdown(responseRoomId, arliAnswer);
            } else {
                matrixClient.sendMarkdown(responseRoomId, "Failed to get response from Arli AI. Status: "
                        + response.statusCode() + ", Body: " + response.body());
            }

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying Arli AI: " + e.getMessage());
        }
    }

    public void queryArliAIUnread(String responseRoomId, String exportRoomId, String sender, ZoneId zoneId,
            String question, String promptPrefix) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            RoomHistoryManager.EventInfo lastRead = historyManager.getReadReceipt(exportRoomId, sender);

            if (lastRead == null) {
                matrixClient.sendMarkdown(responseRoomId, "No read receipt found for you in " + exportRoomId + ".");
                return;
            }

            matrixClient.sendMarkdown(responseRoomId, "Fetching unread messages for you in " + exportRoomId + "...");

            RoomHistoryManager.ChatLogsResult result = historyManager.fetchUnreadMessages(exportRoomId,
                    lastRead.eventId,
                    zoneId);

            if (result.logs.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId, "No unread messages found for you in " + exportRoomId + ".");
                return;
            }

            matrixClient.sendMarkdown(responseRoomId, "Summarizing " + result.logs.size() + " unread messages"
                    + (question != null ? " with question: " + question : "") + "...");

            String prompt = buildPrompt(question, result.logs, promptPrefix);

            String arliApiUrl = "https://api.arliai.com";

            if (arliApiKey == null || arliApiKey.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId, "ARLI_API_KEY is not configured.");
                return;
            }

            List<Map<String, String>> messages = buildMessages(prompt);

            Map<String, Object> arliPayload = Map.of(
                    "model", "Gemma-3-27B-it",
                    "messages", messages,
                    "stream", false);
            String jsonPayload = mapper.writeValueAsString(arliPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(arliApiUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + arliApiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode arliResponse = mapper.readTree(response.body());
                String arliAnswer = arliResponse.path("choices").get(0).path("message").path("content")
                        .asText("No response from Arli AI.");

                arliAnswer = appendMessageLink(arliAnswer, exportRoomId, result.firstEventId);

                matrixClient.sendMarkdown(responseRoomId, arliAnswer);
            } else {
                matrixClient.sendMarkdown(responseRoomId, "Failed to get response from Arli AI. Status: "
                        + response.statusCode() + ", Body: " + response.body());
            }

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error summarizing unread messages: " + e.getMessage());
        }
    }

    public void queryCerebras(String responseRoomId, String exportRoomId, int hours, String fromToken, String question,
            long startTimestamp, ZoneId zoneId, int maxMessages) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            String timeInfo = "";
            if (startTimestamp > 0) {
                String dateStr = java.time.Instant.ofEpochMilli(startTimestamp)
                        .atZone(zoneId)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                timeInfo = "starting at " + dateStr + " (next "
                        + (maxMessages > 0 ? maxMessages + " messages" : hours + "h") + ")";
            } else {
                if (maxMessages > 0) {
                    timeInfo = "last " + maxMessages + " messages";
                } else {
                    timeInfo = "last " + (hours > 0 ? hours + "h" : "all history");
                }
            }
            matrixClient.sendMarkdown(responseRoomId, "Querying Cerebras AI with chat logs from " + exportRoomId + " ("
                    + timeInfo + (question != null ? " and question: " + question : "") + ")...");

            long endTime = startTimestamp > 0 ? startTimestamp + (long) hours * 3600L * 1000L : -1;

            RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryDetailed(exportRoomId, hours,
                    fromToken, startTimestamp, endTime, zoneId, maxMessages);
            if (result.logs.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId,
                        "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            String prompt = buildPrompt(question, result.logs, Prompts.OVERVIEW_PREFIX);

            String cerebrasApiUrl = "https://api.cerebras.ai";

            if (cerebrasApiKey == null || cerebrasApiKey.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId, "CEREBRAS_API_KEY is not configured.");
                return;
            }

            List<Map<String, String>> messages = buildMessages(prompt);

            Map<String, Object> cerebrasPayload = Map.of(
                    "model", "gpt-oss-120b",
                    "messages", messages,
                    "stream", false);
            String jsonPayload = mapper.writeValueAsString(cerebrasPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cerebrasApiUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cerebrasApiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode cerebrasResponse = mapper.readTree(response.body());
                String cerebrasAnswer = cerebrasResponse.path("choices").get(0).path("message").path("content")
                        .asText("No response from Cerebras AI.");

                cerebrasAnswer = appendMessageLink(cerebrasAnswer, exportRoomId, result.firstEventId);

                matrixClient.sendMarkdown(responseRoomId, cerebrasAnswer);
            } else {
                matrixClient.sendMarkdown(responseRoomId, "Failed to get response from Cerebras AI. Status: "
                        + response.statusCode() + ", Body: " + response.body());
            }

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying Cerebras AI: " + e.getMessage());
        }
    }

    private String buildPrompt(String question, List<String> logs, String promptPrefix) {
        String logsStr = String.join("\n", logs);
        if (question != null && !question.isEmpty()) {
            return Prompts.QUESTION_PREFIX + question + Prompts.QUESTION_SUFFIX + logsStr;
        } else {
            return promptPrefix + logsStr;
        }
    }

    private List<Map<String, String>> buildMessages(String prompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", Prompts.SYSTEM_OVERVIEW));
        messages.add(Map.of("role", "user", "content", prompt));
        return messages;
    }

    private String appendMessageLink(String aiAnswer, String exportRoomId, String firstEventId) {
        if (firstEventId != null) {
            String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + firstEventId;
            return aiAnswer + "\n\n" + messageLink;
        }
        return aiAnswer;
    }
}
