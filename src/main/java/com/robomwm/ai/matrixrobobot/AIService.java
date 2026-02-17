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
        public static final String OVERVIEW_PREFIX = "Give a high level overview of the following chat logs. Use only a title and timestamp for each topic and only include one or more chat messages verbatim (with username) as bullet points for each topic; bias to include discovered solutions, unanswered questions, or interesting resources. Don't use table format. Then summarize with bullet points all of the chat at end, including discovered solutions or interesting resources; no complete sentences required, this should be brief and avoid analysis. Your entire response should be faster to read than reading the chat log itself:\n\n";
        public static final String SUMMARY_PREFIX = "Give a high level overview of the following chat logs, only including substantially-discussed topics; bias to include discovered solutions, unanswered questions, or suggested resources. Use only a title and timestamp for each topic; it shouldn't take more than 20 seconds to skim through the topics. Include one chat message verbatim (with username) for each topic. Don't use table format. Then collect a list of all chat messages verbatim (with username) that were not included in the overview that contain discovered solutions, unanswered questions, or suggested resources:\n\n";
        public static final String TLDR_PREFIX = "Provide a very concise summary of the following chat logs that can be read in 15 seconds or less. Make use of bullet points of key topics with timestamp; be extremely brief, no need for complete sentences. Always include topics that are informative towards a discovered solution or resources; if the other topics are significantly discussed, these topics can be added on to increase reading time to no more than 30 seconds. Then directly include the best chat message verbatim; have bias towards one that is informative towards a discovered solution or informative resource:\n\n";
    }

    public void queryAI(String responseRoomId, String exportRoomId, int hours, String fromToken, String question,
            String startEventId, boolean forward, ZoneId zoneId, int maxMessages, String promptPrefix,
            java.util.concurrent.atomic.AtomicBoolean abortFlag, Backend preferredBackend) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            String timeInfo = "";
            if (startEventId != null) {
                timeInfo = (forward ? "after " : "before ") + "message " + startEventId + " (limit "
                        + (maxMessages > 0 ? maxMessages + " messages" : hours + "h") + ")";
            } else {
                if (maxMessages > 0) {
                    timeInfo = "last " + maxMessages + " messages";
                } else {
                    timeInfo = "last " + (hours > 0 ? hours + "h" : "all history");
                }
            }

            String questionPart = (question != null && !question.isEmpty()) ? " and prompt: " + question : "";
            String backendName = preferredBackend == Backend.AUTO ? "Arli AI" : preferredBackend.toString();
            String statusMsg = "Querying " + backendName
                    + " with chat logs from " + exportRoomId + " ("
                    + timeInfo + questionPart + ")...";

            RoomHistoryManager.ChatLogsResult history = historyManager.fetchRoomHistoryRelative(exportRoomId, hours,
                    fromToken, startEventId, forward, zoneId, maxMessages);

            if (history.errorMessage != null) {
                matrixClient.sendMarkdown(responseRoomId, history.errorMessage);
                return;
            }
            if (history.logs.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId,
                        "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            performAIQuery(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag, preferredBackend, statusMsg, timeInfo + questionPart);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying AI: " + e.getMessage());
        }
    }

    private void performAIQuery(String responseRoomId, String exportRoomId, RoomHistoryManager.ChatLogsResult history,
                                String question, String promptPrefix, java.util.concurrent.atomic.AtomicBoolean abortFlag,
                                Backend preferredBackend, String initialStatusMsg, String queryDescription) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            String eventId = matrixClient.sendTextWithEventId(responseRoomId, initialStatusMsg);
            if (eventId == null) return;

            if (abortFlag != null && abortFlag.get()) return;

            String prompt = buildPrompt(question, history.logs, promptPrefix);

            // Fallback Logic
            boolean tryArli = preferredBackend == Backend.AUTO || preferredBackend == Backend.ARLIAI;
            boolean tryCerebras = preferredBackend == Backend.AUTO || preferredBackend == Backend.CEREBRAS;

            boolean msgEdited = false;

            if (tryArli) {
                try {
                    String answer = callArliAI(prompt);
                    answer = appendMessageLink(answer, exportRoomId, history.firstEventId);
                    matrixClient.sendMarkdown(responseRoomId, answer);
                    return; // Success
                } catch (Exception e) {
                    String errorMsg = e.getMessage();
                    System.out.println("ArliAI Error: " + errorMsg);

                    boolean is403 = errorMsg.contains("Status: 403");
                    boolean isContextExceeded = errorMsg.contains("exceeded the maximum context length");

                    if (is403 && isContextExceeded) {
                        String contextInfo = extractContextInfo(errorMsg);
                        matrixClient.updateTextMessage(responseRoomId, eventId,
                                "Arli AI context exceeded" + contextInfo + ". Querying Cerebras with " + queryDescription + "...");
                        msgEdited = true;
                    } else {
                        matrixClient.sendText(responseRoomId, "ArliAI failed: " + errorMsg);
                        if (is403) return; // Don't fallback on other 403 errors
                    }

                    if (preferredBackend == Backend.ARLIAI) return;
                }
            }

            if (abortFlag != null && abortFlag.get()) return;

            if (tryCerebras) {
                if (preferredBackend == Backend.AUTO && !eventId.isEmpty() && !msgEdited) {
                    matrixClient.sendText(responseRoomId, "Querying Cerebras...");
                }

                try {
                    String answer = callCerebras(prompt);
                    answer = appendMessageLink(answer, exportRoomId, history.firstEventId);
                    matrixClient.sendMarkdown(responseRoomId, answer);
                } catch (Exception e) {
                    matrixClient.sendMarkdown(responseRoomId, "Cerebras AI failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error performing AI query: " + e.getMessage());
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

    public void queryAIUnread(String responseRoomId, String exportRoomId, String sender, ZoneId zoneId,
            String question, String promptPrefix, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            String startEventId) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            String lastReadEventId = startEventId;
            if (lastReadEventId == null) {
                RoomHistoryManager.EventInfo lastRead = historyManager.getReadReceipt(exportRoomId, sender);
                if (lastRead == null) {
                    matrixClient.sendMarkdown(responseRoomId, "No read receipt found for you in " + exportRoomId + ".");
                    return;
                }
                lastReadEventId = lastRead.eventId;
            }

            RoomHistoryManager.ChatLogsResult result = historyManager.fetchUnreadMessages(exportRoomId,
                    lastReadEventId,
                    zoneId);

            if (result.logs.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId, "No unread messages found for you in " + exportRoomId + ".");
                return;
            }

            String questionPart = (question != null && !question.isEmpty()) ? " and prompt: " + question : "";
            String statusMsg = "Summarizing " + result.logs.size() + " unread messages" + questionPart + "...";
            String queryDescription = result.logs.size() + " unread messages" + questionPart;

            performAIQuery(responseRoomId, exportRoomId, result, question, promptPrefix, abortFlag, Backend.AUTO, statusMsg, queryDescription);

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
