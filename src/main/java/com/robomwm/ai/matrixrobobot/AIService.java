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

    public enum Backend {
        AUTO, ARLIAI, CEREBRAS
    }

    public static class Prompts {
        public static final String SYSTEM_OVERVIEW = "You provide high level overview of a chat log.";
        public static final String QUESTION_PREFIX = "'";
        public static final String QUESTION_SUFFIX = "' Answer this prompt using these chat logs:\n\n";
        public static final String OVERVIEW_PREFIX = "Give a high level overview of the following chat logs. Use only a title and timestamp for each topic and only include one or more chat messages verbatim (with username) as bullet points for each topic; bias to include discovered solutions or interesting resources. Don't use table format. Then summarize with bullet points all of the chat at end, including discovered solutions or interesting resources; no complete sentences required, this should be brief:\n\n";
        public static final String TLDR_PREFIX = "Provide a very concise summary of the following chat logs that can be read in 15 seconds or less. Make use of bullet points of key topics with timestamp; be extremely brief, no need for complete sentences. Always include topics that are informative towards a discovered solution or resources; if the other topics are significantly discussed, these topics can be added on to increase reading time to no more than 30 seconds. Then directly include the best chat message verbatim; have bias towards one that is informative towards a discovered solution or informative resource:\n\n";
    }

    public void queryAI(String responseRoomId, String exportRoomId, int hours, String fromToken, String question,
            long startTimestamp, ZoneId zoneId, int maxMessages, String promptPrefix,
            java.util.concurrent.atomic.AtomicBoolean abortFlag, Backend preferredBackend) {
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

            String questionPart = (question != null && !question.isEmpty()) ? " and prompt: " + question : "";
            
            String backendName = preferredBackend == Backend.AUTO ? "Arli AI" : preferredBackend.toString(); // Start with Arli if AUTO
            String statusMsg = "Querying " + backendName
                    + " with chat logs from " + exportRoomId + " ("
                    + timeInfo + questionPart + ")...";
            String eventId = matrixClient.sendTextWithEventId(responseRoomId, statusMsg);
            if (eventId == null)
                return; // Failed to send status

            long endTime = startTimestamp > 0 ? startTimestamp + (long) hours * 3600L * 1000L : -1;

            if (abortFlag != null && abortFlag.get())
                return;

            RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryDetailed(exportRoomId, hours,
                    fromToken, startTimestamp, endTime, zoneId, maxMessages);
            if (result.logs.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId,
                        "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            if (abortFlag != null && abortFlag.get())
                return;

            String prompt = buildPrompt(question, result.logs, promptPrefix);

            // Fallback Logic
            boolean tryArli = preferredBackend == Backend.AUTO || preferredBackend == Backend.ARLIAI;
            boolean tryCerebras = preferredBackend == Backend.AUTO || preferredBackend == Backend.CEREBRAS; // If AUTO,
                                                                                                            // we try
                                                                                                            // Cerebras
                                                                                                            // if Arli
                                                                                                            // fails.

            // If explicit Cerebras, only try Cerebras.
            // If explicit Arli, only try Arli.
            // If AUTO, try Arli, then Cerebras.

            boolean msgEdited = false;

            if (tryArli) {
                try {
                    String answer = callArliAI(prompt);
                    answer = appendMessageLink(answer, exportRoomId, result.firstEventId);
                    matrixClient.sendMarkdown(responseRoomId, answer);
                    return; // Success
                } catch (Exception e) {
                    System.out.println("ArliAI Error: " + e.getMessage()); // Log for debugging
                    e.printStackTrace();
                    
                    // Check for specific 403 context limit error
                    if (e.getMessage().contains("exceeded the maximum context length")) {
                        String contextInfo = extractContextInfo(e.getMessage());
                        matrixClient.updateTextMessage(responseRoomId, eventId,
                                "Arli AI context exceeded" + contextInfo + ". Querying Cerebras with " + timeInfo + questionPart + "...");
                        msgEdited = true;
                    } else {
                        matrixClient.sendText(responseRoomId, "ArliAI failed: " + e.getMessage());
                    }

                    if (preferredBackend == Backend.ARLIAI) {
                        return; // Done if only Arli was requested
                    }
                    // else continue to Cerebras if AUTO
                }
            }

            if (abortFlag != null && abortFlag.get())
                return;

            if (tryCerebras) {
                // If we are here in AUTO mode, it means Arli failed or we skipped it.
                // If failed, we might want to announce we are trying Cerebras (if not already
                // done via edit).
                if (preferredBackend == Backend.AUTO && !eventId.isEmpty() && !msgEdited) {
                    // The edit above handles the context limit case. For other errors, we already
                    // printed "ArliAI failed".
                    // Maybe print "Querying Cerebras..."?
                    // The user request said: "keep the error message it prints in chat and logs"
                    // except for 403.
                    matrixClient.sendText(responseRoomId, "Querying Cerebras...");
                }

                try {
                    String answer = callCerebras(prompt);
                    answer = appendMessageLink(answer, exportRoomId, result.firstEventId);
                    matrixClient.sendMarkdown(responseRoomId, answer);
                } catch (Exception e) {
                    matrixClient.sendMarkdown(responseRoomId, "Cerebras AI failed: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying AI: " + e.getMessage());
        }
    }

    private String callArliAI(String prompt) throws Exception {
        String arliApiUrl = "https://api.arliai.com";
        if (arliApiKey == null || arliApiKey.isEmpty()) {
            throw new Exception("ARLI_API_KEY is not configured.");
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
            return arliResponse.path("choices").get(0).path("message").path("content")
                    .asText("No response from Arli AI.");
        } else {
            throw new Exception("Failed to get response from Arli AI. Status: "
                    + response.statusCode() + ", Body: " + response.body());
        }
    }

    public void queryArliAIUnread(String responseRoomId, String exportRoomId, String sender, ZoneId zoneId,
            String question, String promptPrefix, java.util.concurrent.atomic.AtomicBoolean abortFlag) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            RoomHistoryManager.EventInfo lastRead = historyManager.getReadReceipt(exportRoomId, sender);

            if (lastRead == null) {
                matrixClient.sendMarkdown(responseRoomId, "No read receipt found for you in " + exportRoomId + ".");
                return;
            }

            String statusMsg = "Fetching unread messages for you in " + exportRoomId + "...";
            String eventId = matrixClient.sendTextWithEventId(responseRoomId, statusMsg);

            if (abortFlag != null && abortFlag.get())
                return;

            RoomHistoryManager.ChatLogsResult result = historyManager.fetchUnreadMessages(exportRoomId,
                    lastRead.eventId,
                    zoneId);

            if (result.logs.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId, "No unread messages found for you in " + exportRoomId + ".");
                return;
            }

            String questionPart = (question != null && !question.isEmpty()) ? " and prompt: " + question : "";
            
            String summarizingMsg = "Summarizing " + result.logs.size() + " unread messages"
                    + questionPart + "...";
            if (eventId != null) {
                matrixClient.updateTextMessage(responseRoomId, eventId, summarizingMsg);
            } else {
                eventId = matrixClient.sendTextWithEventId(responseRoomId, summarizingMsg);
            }

            String prompt = buildPrompt(question, result.logs, promptPrefix);

            if (abortFlag != null && abortFlag.get())
                return;

            // Trigger fallback logic manually or reuse callArliAI/callCerebras
            // Since this is specifically "queryArliAIUnread", historically it was Arli
            // only.
            // But user said "automatically try using cerberus... Allow for !summary
            // command... fallback logic the new normal"
            // So we should probably try fallback here too.

            try {
                String answer = callArliAI(prompt);
                answer = appendMessageLink(answer, exportRoomId, result.firstEventId);
                matrixClient.sendMarkdown(responseRoomId, answer);
            } catch (Exception e) {
                System.out.println("ArliAI Unread Error: " + e.getMessage());
                e.printStackTrace();
                
                if (e.getMessage().contains("exceeded the maximum context length")) {
                    String contextInfo = extractContextInfo(e.getMessage());
                    matrixClient.updateTextMessage(responseRoomId, eventId,
                            "Arli AI context exceeded" + contextInfo + ". Querying Cerebras with " + result.logs.size()
                                    + " unread messages...");
                } else {
                    matrixClient.sendText(responseRoomId, "ArliAI failed: " + e.getMessage());
                    matrixClient.sendText(responseRoomId, "Querying Cerebras...");
                }

                try {
                    String answer = callCerebras(prompt);
                    answer = appendMessageLink(answer, exportRoomId, result.firstEventId);
                    matrixClient.sendMarkdown(responseRoomId, answer);
                } catch (Exception ex) {
                    matrixClient.sendMarkdown(responseRoomId, "Cerebras AI failed: " + ex.getMessage());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error summarizing unread messages: " + e.getMessage());
        }
    }

    private String callCerebras(String prompt) throws Exception {
        String cerebrasApiUrl = "https://api.cerebras.ai";
        if (cerebrasApiKey == null || cerebrasApiKey.isEmpty()) {
            throw new Exception("CEREBRAS_API_KEY is not configured.");
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
            return cerebrasResponse.path("choices").get(0).path("message").path("content")
                    .asText("No response from Cerebras AI.");
        } else {
            throw new Exception("Failed to get response from Cerebras AI. Status: "
                    + response.statusCode() + ", Body: " + response.body());
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

    private String extractContextInfo(String errorMessage) {
        try {
            int flags = java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE;
            
            // Pattern: "exceeded the maximum context length ... (used/limit)"
            // Matches: "exceeded the maximum context length for FREE (33117/16384)"
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("exceeded.*?context.*?\\((\\d+)/(\\d+)\\)", flags);
            java.util.regex.Matcher m = p.matcher(errorMessage);
            if (m.find()) {
                return " (" + m.group(1) + "/" + m.group(2) + ")"; // Used/Limit
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }
}
