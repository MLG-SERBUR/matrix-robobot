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
import java.util.Arrays;
import java.util.Random;

public class AIService {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String homeserver;
    private final String accessToken;
    private final String arliApiKey;
    private final String cerebrasApiKey;
    private final RoomHistoryManager historyManager;
    private final Random random;
    public static final List<String> ARLI_MODELS = Arrays.asList(
            "Gemma-3-27B-ArliAI-RPMax-v3",
            "Gemma-3-27B-ArliAI-RPMax-v3",
            "Gemma-3-27B-Big-Tiger-v3",
            "Gemma-3-27B-Big-Tiger-v3",
            "Gemma-3-27B-CardProjector-v4",
            "Gemma-3-27B-CardProjector-v4",
            "Gemma-3-27B-Glitter",
            "Gemma-3-27B-Glitter",
            "Gemma-3-27B-it",
            "Gemma-3-27B-it",
            "Gemma-3-27B-it-Abliterated",
            "Gemma-3-27B-it-Abliterated"
    );
    public static final List<String> CEREBRAS_MODELS = Arrays.asList("gpt-oss-120b");

    public AIService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken, String arliApiKey,
            String cerebrasApiKey) {
        this.client = client;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.arliApiKey = arliApiKey;
        this.cerebrasApiKey = cerebrasApiKey;
        this.historyManager = new RoomHistoryManager(client, mapper, homeserver, accessToken);
        this.random = new Random();
    }

    public enum Backend {
        AUTO, ARLIAI, CEREBRAS
    }

    public static class Prompts {
        public static final String SYSTEM_OVERVIEW = "You provide high level overview of a chat log.";
        public static final String QUESTION_PREFIX = "'";
        public static final String QUESTION_SUFFIX = "' Answer this prompt (don't use tables, table markdown is not supported) using these chat logs:\n\n";
        public static final String OVERVIEW_PREFIX = "Give a high level overview of the following chat logs. Use only a title and timestamp for each topic and only include one or more chat messages verbatim (with username) as bullet points for each topic; bias to include discovered solutions, unanswered questions, or interesting resources. Don't use table format. Then summarize with bullet points all of the chat at end, including discovered solutions or interesting resources; no complete sentences required, this should be brief and avoid analysis. Your entire response should be faster to read than reading the chat log itself:\n\n";
        public static final String SUMMARY_PREFIX = "Give a concise, high level overview of the following chat logs. No need for complete sentences. Use only a title and timestamp for each topic; include one chat message verbatim (with username) for each topic. Should take no more than a minute to read, ideally target less than 30 seconds. No table format:\n\n";
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

            performAIQuery(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag, preferredBackend);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying AI: " + e.getMessage());
        }
    }

    private void performAIQuery(String responseRoomId, String exportRoomId, RoomHistoryManager.ChatLogsResult history,
                                String question, String promptPrefix, java.util.concurrent.atomic.AtomicBoolean abortFlag,
                                Backend preferredBackend) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            String arliModel = getRandomModel(ARLI_MODELS);
            String cerebrasModel = getRandomModel(CEREBRAS_MODELS);

            String questionPart = (question != null && !question.isEmpty()) ? " and prompt: " + question : "";
            String backendName = preferredBackend == Backend.CEREBRAS ? "Cerebras (" + cerebrasModel + ")" : "Arli AI (" + arliModel + ")";
            String queryDescription = history.logs.size() + " messages";
            String initialStatusMsg = "Querying " + backendName + " with " + queryDescription + questionPart + "...";

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
                    String answer = callArliAI(prompt, arliModel);
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
                                "Arli AI (" + arliModel + ") context exceeded" + contextInfo + ". Querying Cerebras (" + cerebrasModel + ") with " + queryDescription + questionPart + "...");
                        msgEdited = true;
                    } else {
                        matrixClient.sendText(responseRoomId, "ArliAI (" + arliModel + ") failed: " + errorMsg);
                        if (is403) return; // Don't fallback on other 403 errors
                    }

                    if (preferredBackend == Backend.ARLIAI) return;
                }
            }

            if (abortFlag != null && abortFlag.get()) return;

            if (tryCerebras) {
                if (preferredBackend == Backend.AUTO && !eventId.isEmpty() && !msgEdited) {
                    matrixClient.sendText(responseRoomId, "Querying Cerebras (" + cerebrasModel + ")...");
                }

                try {
                    String answer = callCerebras(prompt, cerebrasModel);
                    answer = appendMessageLink(answer, exportRoomId, history.firstEventId);
                    matrixClient.sendMarkdown(responseRoomId, answer);
                } catch (Exception e) {
                    matrixClient.sendMarkdown(responseRoomId, "Cerebras AI (" + cerebrasModel + ") failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error performing AI query: " + e.getMessage());
        }
    }

    private String callArliAI(String prompt, String model) throws Exception {
        String arliApiUrl = "https://api.arliai.com";
        if (arliApiKey == null || arliApiKey.isEmpty()) {
            throw new Exception("ARLI_API_KEY is not configured.");
        }

        List<Map<String, String>> messages = buildMessages(prompt);

        Map<String, Object> arliPayload = Map.of(
                "model", model,
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
                    .asText("No response from Arli AI (" + model + ").");
        } else {
            throw new Exception("Failed to get response from Arli AI (" + model + "). Status: "
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

            performAIQuery(responseRoomId, exportRoomId, result, question, promptPrefix, abortFlag, Backend.AUTO);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error summarizing unread messages: " + e.getMessage());
        }
    }

    public void queryAsk(String responseRoomId, String exportRoomId, String fromToken, String question,
                         java.util.concurrent.atomic.AtomicBoolean abortFlag) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            // ~16k tokens is roughly 40k-60k characters depending on content. 
            // 60k characters resulted in ~23k tokens in practice, so we use 40k to be safe.
            int charLimit = 40000;
            RoomHistoryManager.ChatLogsResult history = historyManager.fetchRoomHistoryUntilLimit(exportRoomId,
                    fromToken, charLimit, false, null);

            if (history.logs.isEmpty()) {
                matrixClient.sendMarkdown(responseRoomId, "No chat logs found in " + exportRoomId + ".");
                return;
            }

            performAIQuery(responseRoomId, exportRoomId, history, question, "", abortFlag, Backend.AUTO);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying AI: " + e.getMessage());
        }
    }

    private String callCerebras(String prompt, String model) throws Exception {
        String cerebrasApiUrl = "https://api.cerebras.ai";
        if (cerebrasApiKey == null || cerebrasApiKey.isEmpty()) {
            throw new Exception("CEREBRAS_API_KEY is not configured.");
        }

        List<Map<String, String>> messages = buildMessages(prompt);

        Map<String, Object> cerebrasPayload = Map.of(
                "model", model,
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
                    .asText("No response from Cerebras AI (" + model + ").");
        } else {
            throw new Exception("Failed to get response from Cerebras AI (" + model + "). Status: "
                    + response.statusCode() + ", Body: " + response.body());
        }
    }

    private String getRandomModel(List<String> models) {
        if (models == null || models.isEmpty()) {
            return "unknown-model";
        }
        return models.get(random.nextInt(models.size()));
    }

    private static final List<String> IGNORED_DOMAINS = List.of(
            "pixiv.net",
            "donmai.us");

    private static final List<String> IGNORED_USERS = List.of(
            "@bot:kitty.haus");

    private String buildPrompt(String question, List<String> logs, String promptPrefix) {
        List<String> effectiveLogs = logs;

        // If no question is provided, filter out lines containing ignored domains
        if (question == null || question.isEmpty()) {
            effectiveLogs = new ArrayList<>();
            for (String log : logs) {
                boolean ignore = false;
                for (String domain : IGNORED_DOMAINS) {
                    if (log.toLowerCase().contains(domain.toLowerCase())) {
                        ignore = true;
                        break;
                    }
                }
                if (!ignore) {
                    for (String user : IGNORED_USERS) {
                        if (log.contains("<" + user + ">")) {
                            ignore = true;
                            break;
                        }
                    }
                }
                if (!ignore) {
                    effectiveLogs.add(log);
                }
            }
        }

        String logsStr = String.join("\n", effectiveLogs);
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
