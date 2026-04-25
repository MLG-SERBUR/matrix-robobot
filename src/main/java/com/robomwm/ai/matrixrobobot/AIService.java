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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AIService {
    protected final HttpClient client;
    protected final ObjectMapper mapper;
    protected final String homeserver;
    protected final String accessToken;
    protected final String arliApiKey;
    protected final String cerebrasApiKey;
    protected final RoomHistoryManager historyManager;
    protected final Random random;
    public static final int AI_TIMEOUT_SECONDS = 20 * 60;
    public static final List<String> ARLI_MODELS = Arrays.asList(
            "Qwen3.5-27B-Derestricted"
    );
    public static final List<String> CEREBRAS_MODELS = Arrays.asList("qwen-3-235b-a22b-instruct-2507");

    private static class AIContextExceededException extends Exception {
        private final String contextInfo;

        AIContextExceededException(String message) {
            this(message, "");
        }

        AIContextExceededException(String message, String contextInfo) {
            super(message);
            this.contextInfo = contextInfo == null ? "" : contextInfo;
        }

        String getContextInfo() {
            return contextInfo;
        }
    }

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
        public static final String OVERVIEW_PREFIX = "Give a concise, high level overview of the following chat logs. No need for complete sentences. Use only a title and timestamp for each topic; include one chat message verbatim (or more, only if necessary) with username for each topic. No table format:\n\n";
        public static final String SUMMARY_PREFIX = "Summarize chat logs with bullet points prefixed with timestamp, no table, using caveman style. Caveman style: Terse like caveman. Technical substance exact. Only fluff die. Drop: articles, filler (just/really/basically), pleasantries, hedging. Fragments OK. Short synonyms. Bias including discovered solutions or interesting technical resources; bias excluding filler content:\n\n";
        public static final String TLDR_PREFIX = "Provide a very concise summary of the following chat logs that can be read in 15 seconds or less. Make use of bullet points of key topics with timestamp; be extremely brief, no need for complete sentences. Always include topics that are informative towards a discovered solution or resources; if the other topics are significantly discussed, these topics can be added on to increase reading time to no more than 30 seconds. Then directly include the best chat message verbatim; have bias towards one that is informative towards a discovered solution or informative resource:\n\n";
        public static final String DEBUGAI_PREFIX = "\n\n";
    }

    public void queryAI(String responseRoomId, String exportRoomId, int hours, String fromToken, String question,
            String startEventId, boolean forward, ZoneId zoneId, int maxMessages, String promptPrefix,
            java.util.concurrent.atomic.AtomicBoolean abortFlag, Backend preferredBackend) {
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
            String gatherMsg = "\uD83D\uDCE8 Gathering " + timeInfo + "...";
            String statusEventId = matrixClient.sendNoticeWithEventId(responseRoomId, gatherMsg);

            // Create progress callback that updates every 5 seconds
            final String fStatusEventId = statusEventId;
            final AtomicLong lastProgressUpdate = new AtomicLong(System.currentTimeMillis());
            RoomHistoryManager.ProgressCallback progressCallback = (msgCount, estTokens) -> {
                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate.get() >= 5000) {
                    lastProgressUpdate.set(now);
                    String tokenStr = estTokens >= 1000 ? String.format("%.1fk", estTokens / 1000.0) : String.valueOf(estTokens);
                    matrixClient.updateNoticeMessage(responseRoomId, fStatusEventId,
                            "\uD83D\uDCE8 Gathering " + timeInfo + "... (" + msgCount + " gathered, ~" + tokenStr + " tokens)");
                }
            };

            RoomHistoryManager.ChatLogsResult history = fetchHistoryForQuery(exportRoomId, hours, fromToken,
                    startEventId, forward, zoneId, maxMessages, abortFlag, progressCallback);

            if (history.errorMessage != null) {
                matrixClient.updateNoticeMessage(responseRoomId, statusEventId, history.errorMessage);
                return;
            }
            if (history.logs.isEmpty()) {
                matrixClient.updateNoticeMessage(responseRoomId, statusEventId,
                        "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            history = prepareHistoryForQuery(responseRoomId, exportRoomId, history, abortFlag, statusEventId);
            if (history == null || history.logs.isEmpty()) {
                return;
            }

            performAIQuery(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag, preferredBackend, null, AI_TIMEOUT_SECONDS, statusEventId);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying AI: " + e.getMessage());
        }
    }

    protected RoomHistoryManager.ChatLogsResult fetchHistoryForQuery(String exportRoomId, int hours, String fromToken,
            String startEventId, boolean forward, ZoneId zoneId, int maxMessages,
            java.util.concurrent.atomic.AtomicBoolean abortFlag, RoomHistoryManager.ProgressCallback progressCallback) {
        return historyManager.fetchRoomHistoryRelative(exportRoomId, hours, fromToken, startEventId, forward, zoneId,
                maxMessages, false, true, abortFlag, progressCallback);
    }

    protected RoomHistoryManager.ChatLogsResult prepareHistoryForQuery(String responseRoomId, String exportRoomId,
            RoomHistoryManager.ChatLogsResult history, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            String statusEventId) {
        return history;
    }

    protected void performAIQuery(String responseRoomId, String exportRoomId, RoomHistoryManager.ChatLogsResult history,
                                String question, String promptPrefix, java.util.concurrent.atomic.AtomicBoolean abortFlag,
                                Backend preferredBackend, String forcedModel, int timeoutSeconds, String statusEventId) {
        if (abortFlag != null && abortFlag.get()) return;
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            String arliModel = (preferredBackend == Backend.ARLIAI && forcedModel != null) ? forcedModel : getRandomModel(ARLI_MODELS);
            String cerebrasModel = getRandomModel(CEREBRAS_MODELS);

            String questionPart = (question != null && !question.isEmpty()) ? " and prompt: " + question : "";
            String backendName = preferredBackend == Backend.CEREBRAS ? "Cerebras (" + cerebrasModel + ")" : "Arli AI (" + arliModel + ")";
            String queryDescription = history.logs.size() + " messages";
            String queryStatusMsg = "\u23F3 Querying " + backendName + " with " + queryDescription + questionPart;

            // Reuse existing status message if available, otherwise send a new one
            String eventId;
            if (statusEventId != null) {
                matrixClient.updateNoticeMessage(responseRoomId, statusEventId, queryStatusMsg);
                eventId = statusEventId;
            } else {
                eventId = matrixClient.sendNoticeWithEventId(responseRoomId, queryStatusMsg);
            }
            if (eventId == null) return;

            if (abortFlag != null && abortFlag.get()) {
                matrixClient.updateNoticeMessage(responseRoomId, eventId, queryStatusMsg + " [ABORTED]");
                return;
            }

            boolean skipSystem = Prompts.DEBUGAI_PREFIX.equals(promptPrefix);
            String prompt = buildPrompt(question, history.logs, promptPrefix);

            // Fallback Logic
            boolean tryArli = preferredBackend == Backend.AUTO || preferredBackend == Backend.ARLIAI;
            boolean tryCerebras = preferredBackend == Backend.AUTO || preferredBackend == Backend.CEREBRAS;

            boolean msgEdited = false;
            String arliErrorMsg = null;
            AIContextExceededException contextExceeded = null;
            boolean attemptedBackend = false;
            boolean allAttemptedBackendsContextExceeded = true;

            if (tryArli) {
                attemptedBackend = true;
                try {
                    callArliAI(prompt, arliModel, skipSystem, responseRoomId, exportRoomId, history.firstEventId, timeoutSeconds, abortFlag);
                    return; // Success
                } catch (Exception e) {
                    arliErrorMsg = e.getMessage() == null ? e.toString() : e.getMessage();
                    System.out.println("ArliAI Error: " + arliErrorMsg);

                    boolean is403 = arliErrorMsg.contains("Status: 403");
                    boolean isContextExceeded = isContextExceededMessage(arliErrorMsg);

                    if (isContextExceeded) {
                        String contextInfo = extractContextInfo(arliErrorMsg);
                        contextExceeded = new AIContextExceededException(
                                "Arli AI (" + arliModel + ") context exceeded" + contextInfo + ".",
                                contextInfo);
                        if (abortFlag != null && abortFlag.get()) return;
                        if (preferredBackend == Backend.AUTO && tryCerebras && cerebrasApiKey != null && !cerebrasApiKey.isEmpty()) {
                            matrixClient.updateNoticeMessage(responseRoomId, eventId,
                                    "Arli AI (" + arliModel + ") context exceeded" + contextInfo + ". Querying Cerebras (" + cerebrasModel + ") with " + queryDescription + questionPart);
                            msgEdited = true;
                        }
                    } else {
                        allAttemptedBackendsContextExceeded = false;
                        if (abortFlag != null && abortFlag.get()) return;
                        matrixClient.sendNotice(responseRoomId, "ArliAI (" + arliModel + ") failed: " + arliErrorMsg);
                        if (is403) return; // Don't fallback on other 403 errors
                    }

                    if (preferredBackend != Backend.AUTO) return;
                }
            }

            if (abortFlag != null && abortFlag.get()) return;

            if (tryCerebras && cerebrasApiKey != null && !cerebrasApiKey.isEmpty()) {
                attemptedBackend = true;
                if (abortFlag != null && abortFlag.get()) return;

                if (preferredBackend == Backend.AUTO && !eventId.isEmpty() && !msgEdited) {
                    if (abortFlag != null && abortFlag.get()) return;
                    matrixClient.sendNotice(responseRoomId, "Querying Cerebras (" + cerebrasModel + ")...");
                }

                try {
                    String answer = callCerebras(prompt, cerebrasModel, skipSystem, timeoutSeconds);
                    answer = appendMessageLink(answer, exportRoomId, history.firstEventId);
                    matrixClient.sendMarkdown(responseRoomId, answer);
                    return;
                } catch (AIContextExceededException e) {
                    contextExceeded = e;
                } catch (Exception e) {
                    allAttemptedBackendsContextExceeded = false;
                    matrixClient.sendMarkdown(responseRoomId, "Cerebras AI (" + cerebrasModel + ") failed: " + e.getMessage());
                }
                if (preferredBackend != Backend.AUTO) return;
            } else if (arliErrorMsg != null && contextExceeded == null) {
                // ArliAI failed and Cerebras is not available (e.g., VisionAI), send ArliAI error
                matrixClient.sendMarkdown(responseRoomId, "Arli AI (" + arliModel + ") failed: " + arliErrorMsg);
                return;
            }

            if (attemptedBackend && allAttemptedBackendsContextExceeded && contextExceeded != null) {
                handleContextExceeded(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag,
                        preferredBackend, forcedModel, timeoutSeconds, statusEventId, contextExceeded.getMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error performing AI query: " + e.getMessage());
        }
    }

    protected void handleContextExceeded(String responseRoomId, String exportRoomId, RoomHistoryManager.ChatLogsResult history,
            String question, String promptPrefix, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            Backend preferredBackend, String forcedModel, int timeoutSeconds, String statusEventId,
            String contextExceededMessage) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        matrixClient.sendMarkdown(responseRoomId, contextExceededMessage);
    }

    protected String callArliAI(String prompt, String model, boolean skipSystem, String responseRoomId, String exportRoomId, String firstEventId, int timeoutSeconds, java.util.concurrent.atomic.AtomicBoolean abortFlag) throws Exception {
        String arliApiUrl = "https://api.arliai.com";
        if (arliApiKey == null || arliApiKey.isEmpty()) {
            throw new Exception("ARLI_API_KEY is not configured.");
        }

        List<Map<String, String>> messages = buildMessages(prompt, skipSystem);
        messages.add(Map.of("role", "assistant", "content", "<think></think>\n"));

        Map<String, Object> arliPayload = Map.of(
                "model", model,
                "messages", messages,
                "stream", true,
                "output_kind", "delta");
        String jsonPayload = mapper.writeValueAsString(arliPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(arliApiUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + arliApiKey)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return streamArliAIResponse(request, responseRoomId, exportRoomId, firstEventId, "ArliAI", abortFlag);
    }

    protected String callArliAIStreamingToEvent(String prompt, String model, boolean skipSystem, String responseRoomId,
            String[] eventIdHolder, String footer, int timeoutSeconds, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            boolean useNotice) throws Exception {
        String arliApiUrl = "https://api.arliai.com";
        if (arliApiKey == null || arliApiKey.isEmpty()) {
            throw new Exception("ARLI_API_KEY is not configured.");
        }

        List<Map<String, String>> messages = buildMessages(prompt, skipSystem);
        messages.add(Map.of("role", "assistant", "content", "<think></think>\n"));

        Map<String, Object> arliPayload = Map.of(
                "model", model,
                "messages", messages,
                "stream", true,
                "output_kind", "delta");
        String jsonPayload = mapper.writeValueAsString(arliPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(arliApiUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + arliApiKey)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return streamArliAIResponseToEvent(request, responseRoomId, eventIdHolder, "ArliAI", abortFlag, footer, useNotice);
    }

    protected String callArliAIBlocking(String prompt, String model, boolean skipSystem, int timeoutSeconds) throws Exception {
        String arliApiUrl = "https://api.arliai.com";
        if (arliApiKey == null || arliApiKey.isEmpty()) {
            throw new Exception("ARLI_API_KEY is not configured.");
        }

        List<Map<String, String>> messages = buildMessages(prompt, skipSystem);
        messages.add(Map.of("role", "assistant", "content", "<think></think>\n"));

        Map<String, Object> arliPayload = Map.of(
                "model", model,
                "messages", messages,
                "stream", false);
        String jsonPayload = mapper.writeValueAsString(arliPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(arliApiUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + arliApiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new Exception("Status: " + response.statusCode() + " Body: " + response.body());
        }

        try {
            JsonNode arliResponse = mapper.readTree(response.body());
            JsonNode choice = arliResponse.path("choices").get(0);
            if (choice == null) {
                throw new Exception("Missing 'choices' array");
            }

            String text = choice.path("message").path("content").asText(null);
            if (text == null || text.trim().isEmpty()) {
                text = choice.path("message").path("reasoning").asText(null);
            }
            if (text == null || text.trim().isEmpty()) {
                text = choice.path("message").path("reasoning_content").asText(null);
            }
            if (text == null || text.trim().isEmpty()) {
                throw new Exception("No response from Arli AI (" + model + ").");
            }

            return text.replaceAll("(?s)<think>.*?</think>", "").trim();
        } catch (Exception e) {
            throw new Exception("Unexpected response from Arli AI (" + model + "). Body: " + response.body(), e);
        }
    }

    protected String streamArliAIResponse(HttpRequest request, String responseRoomId, String exportRoomId, String firstEventId, String aiName, java.util.concurrent.atomic.AtomicBoolean abortFlag) throws Exception {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        String[] eventIdHolder = new String[]{null};

        StringBuilder reasoning = new StringBuilder();
        StringBuilder responseContent = new StringBuilder();
        long lastUpdate = System.currentTimeMillis();
        final long startTime = System.currentTimeMillis();

        int updateCount = 0;
        String[] clockFaces = {"🕛", "🕧", "🕐", "🕜", "🕑", "🕝", "🕒", "🕞", "🕓", "🕟", "🕔", "🕠", "🕕", "🕡", "🕖", "🕢", "🕗", "🕣", "🕘", "🕤", "🕙", "🕥", "🕚", "🕦"};

        try {
            System.out.println("Starting " + aiName + " streaming request...");
            HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            
            if (response.statusCode() != 200) {
                String errorBody = response.body().collect(java.util.stream.Collectors.joining("\n"));
                throw new Exception("Status: " + response.statusCode() + " Body: " + errorBody);
            }
            
            try (java.util.stream.Stream<String> lines = response.body()) {
                java.util.Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    if (abortFlag != null && abortFlag.get()) {
                        System.out.println(aiName + " streaming aborted by flag.");
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
                                        eventIdHolder[0] = matrixClient.sendMarkdownNoticeWithEventId(responseRoomId, output + " " + indicator);
                                    } else {
                                        matrixClient.updateMarkdownNoticeMessage(responseRoomId, eventIdHolder[0], output + " " + indicator);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println(aiName + " Stream Parse Error: " + e.getMessage() + " | Line: " + line);
                        }
                    } else if (data.contains("[DONE]")) {
                        System.out.println(aiName + " streaming finished normally ([DONE] received).");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error during " + aiName + " streaming call: " + e.getMessage());
            e.printStackTrace();
            throw new Exception("Error during " + aiName + " streaming: " + e.getMessage(), e);
        }

        if (responseContent.length() == 0 && reasoning.length() == 0) {
            throw new Exception("No response received from " + aiName + ".");
        }

        System.out.println(aiName + " Final State - Content size: " + responseContent.length() + ", Reasoning size: " + reasoning.length());
        
        String finalOutput;
        if (responseContent.toString().trim().isEmpty()) {
            if (reasoning.length() > 0) {
                System.out.println(aiName + ": Content is empty, falling back to trimmed reasoning.");
                String trimmed = trimReasoning(reasoning.toString());
                finalOutput = "> " + trimmed.replace("\n", "\n> ") + "\n\n**" + aiName + ": No final response was generated.**";
            } else {
                finalOutput = "**" + aiName + " Error: No final response was generated.**";
            }
        } else {
            finalOutput = responseContent.toString();
        }

        if (finalOutput.length() > 16000) {
            finalOutput = finalOutput.substring(0, 15900) + "... [TRUNCATED]";
        }

        String answer = appendMessageLink(finalOutput, exportRoomId, firstEventId);
        if (eventIdHolder[0] == null) {
            matrixClient.sendMarkdownWithEventId(responseRoomId, answer);
        } else {
            matrixClient.updateMarkdownMessage(responseRoomId, eventIdHolder[0], answer);
        }
        return finalOutput;
    }

    protected String streamArliAIResponseToEvent(HttpRequest request, String responseRoomId, String[] eventIdHolder,
            String aiName, java.util.concurrent.atomic.AtomicBoolean abortFlag, String footer, boolean useNotice) throws Exception {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);

        StringBuilder reasoning = new StringBuilder();
        StringBuilder responseContent = new StringBuilder();
        long lastUpdate = System.currentTimeMillis();
        final long startTime = System.currentTimeMillis();

        int updateCount = 0;
        String[] clockFaces = {"🕛", "🕧", "🕐", "🕜", "🕑", "🕝", "🕒", "🕞", "🕓", "🕟", "🕔", "🕠", "🕕", "🕡", "🕖", "🕢", "🕗", "🕣", "🕘", "🕤", "🕙", "🕥", "🕚", "🕦"};

        try {
            System.out.println("Starting " + aiName + " streaming request...");
            HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                String errorBody = response.body().collect(java.util.stream.Collectors.joining("\n"));
                throw new Exception("Status: " + response.statusCode() + " Body: " + errorBody);
            }

            try (java.util.stream.Stream<String> lines = response.body()) {
                java.util.Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    if (abortFlag != null && abortFlag.get()) {
                        System.out.println(aiName + " streaming aborted by flag.");
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
                                    String rendered = buildStreamingOutput(reasoning, responseContent, footer, clockFaces, updateCount++, startTime);
                                    if (eventIdHolder[0] == null) {
                                        eventIdHolder[0] = useNotice
                                                ? matrixClient.sendMarkdownNoticeWithEventId(responseRoomId, rendered)
                                                : matrixClient.sendMarkdownWithEventId(responseRoomId, rendered);
                                    } else if (useNotice) {
                                        matrixClient.updateMarkdownNoticeMessage(responseRoomId, eventIdHolder[0], rendered);
                                    } else {
                                        matrixClient.updateMarkdownMessage(responseRoomId, eventIdHolder[0], rendered);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println(aiName + " Stream Parse Error: " + e.getMessage() + " | Line: " + line);
                        }
                    } else if (data.contains("[DONE]")) {
                        System.out.println(aiName + " streaming finished normally ([DONE] received).");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error during " + aiName + " streaming call: " + e.getMessage());
            e.printStackTrace();
            throw new Exception("Error during " + aiName + " streaming: " + e.getMessage(), e);
        }

        if (responseContent.length() == 0 && reasoning.length() == 0) {
            throw new Exception("No response received from " + aiName + ".");
        }

        String finalOutput;
        if (responseContent.toString().trim().isEmpty()) {
            if (reasoning.length() > 0) {
                String trimmed = trimReasoning(reasoning.toString());
                finalOutput = "> " + trimmed.replace("\n", "\n> ") + "\n\n**" + aiName + ": No final response was generated.**";
            } else {
                finalOutput = "**" + aiName + " Error: No final response was generated.**";
            }
        } else {
            finalOutput = responseContent.toString();
        }

        if (finalOutput.length() > 16000) {
            finalOutput = finalOutput.substring(0, 15900) + "... [TRUNCATED]";
        }

        if (footer != null && !footer.isEmpty()) {
            finalOutput = finalOutput + "\n\n" + footer;
        }

        if (eventIdHolder[0] == null) {
            eventIdHolder[0] = useNotice
                    ? matrixClient.sendMarkdownNoticeWithEventId(responseRoomId, finalOutput)
                    : matrixClient.sendMarkdownWithEventId(responseRoomId, finalOutput);
        } else if (useNotice) {
            matrixClient.updateMarkdownNoticeMessage(responseRoomId, eventIdHolder[0], finalOutput);
        } else {
            matrixClient.updateMarkdownMessage(responseRoomId, eventIdHolder[0], finalOutput);
        }
        return finalOutput;
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

            // Send immediate status message
            String gatherMsg = "\uD83D\uDCE8 Gathering unread messages...";
            String statusEventId = matrixClient.sendNoticeWithEventId(responseRoomId, gatherMsg);

            // Create progress callback that updates every 5 seconds
            final String fStatusEventId = statusEventId;
            final AtomicLong lastProgressUpdate = new AtomicLong(System.currentTimeMillis());
            RoomHistoryManager.ProgressCallback progressCallback = (msgCount, estTokens) -> {
                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate.get() >= 5000) {
                    lastProgressUpdate.set(now);
                    String tokenStr = estTokens >= 1000 ? String.format("%.1fk", estTokens / 1000.0) : String.valueOf(estTokens);
                    matrixClient.updateNoticeMessage(responseRoomId, fStatusEventId,
                            "\uD83D\uDCE8 Gathering unread messages... (" + msgCount + " gathered, ~" + tokenStr + " tokens)");
                }
            };

            RoomHistoryManager.ChatLogsResult result = historyManager.fetchUnreadMessages(exportRoomId,
                    lastReadEventId,
                    zoneId, true, abortFlag, progressCallback);

            if (result.logs.isEmpty()) {
                matrixClient.updateNoticeMessage(responseRoomId, statusEventId,
                        "No unread messages found for you in " + exportRoomId + ".");
                return;
            }

            performAIQuery(responseRoomId, exportRoomId, result, question, promptPrefix, abortFlag, Backend.AUTO, null, AI_TIMEOUT_SECONDS, statusEventId);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error summarizing unread messages: " + e.getMessage());
        }
    }

    public void queryAsk(String responseRoomId, String exportRoomId, String fromToken, String question, ZoneId zoneId,
                         java.util.concurrent.atomic.AtomicBoolean abortFlag, String forcedModel, int timeoutSeconds, Backend preferredBackend) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            // Target context window for Arli AI is 16k tokens.
            // We reserve ~4000 tokens for the generated response to be safe.
            int targetPromptTokens = 12000;
            
            // Calculate base tokens consumed by prompts and the user's question
            String emptyPrompt = buildPrompt(question, new ArrayList<>(), "");
            int chatFormatOverhead = 20; // Special tokens: BOS/EOS, role markers (im_start/im_end), separators
            int baseTokens = RoomHistoryManager.estimateTokens(Prompts.SYSTEM_OVERVIEW) + 
                             RoomHistoryManager.estimateTokens(emptyPrompt) +
                             chatFormatOverhead;
            
            int tokenLimit = Math.max(1000, targetPromptTokens - baseTokens);

            // Send immediate status message
            String gatherMsg = "\uD83D\uDCE8 Gathering messages (up to ~" + (tokenLimit >= 1000 ? String.format("%.1fk", tokenLimit / 1000.0) : tokenLimit) + " tokens)...";
            String statusEventId = matrixClient.sendNoticeWithEventId(responseRoomId, gatherMsg);

            // Create progress callback that updates every 5 seconds
            final String fStatusEventId = statusEventId;
            final AtomicLong lastProgressUpdate = new AtomicLong(System.currentTimeMillis());
            RoomHistoryManager.ProgressCallback progressCallback = (msgCount, estTokens) -> {
                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate.get() >= 5000) {
                    lastProgressUpdate.set(now);
                    String tokenStr = estTokens >= 1000 ? String.format("%.1fk", estTokens / 1000.0) : String.valueOf(estTokens);
                    matrixClient.updateNoticeMessage(responseRoomId, fStatusEventId,
                            "\uD83D\uDCE8 Gathering messages... (" + msgCount + " gathered, ~" + tokenStr + " tokens)");
                }
            };

            RoomHistoryManager.ChatLogsResult history = historyManager.fetchRoomHistoryUntilLimit(exportRoomId,
                    fromToken, tokenLimit, true, zoneId, true, abortFlag, progressCallback);

            if (history.logs.isEmpty()) {
                matrixClient.updateNoticeMessage(responseRoomId, statusEventId,
                        "No chat logs found in " + exportRoomId + ".");
                return;
            }

            performAIQuery(responseRoomId, exportRoomId, history, question, "", abortFlag, preferredBackend, forcedModel, timeoutSeconds, statusEventId);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdown(responseRoomId, "Error querying AI: " + e.getMessage());
        }
    }

    protected String callCerebras(String prompt, String model, boolean skipSystem, int timeoutSeconds) throws Exception {
        String cerebrasApiUrl = "https://api.cerebras.ai";
        if (cerebrasApiKey == null || cerebrasApiKey.isEmpty()) {
            throw new Exception("CEREBRAS_API_KEY is not configured.");
        }

        List<Map<String, String>> messages = buildMessages(prompt, skipSystem);

        Map<String, Object> cerebrasPayload = Map.of(
                "model", model,
                "messages", messages,
                "stream", false);
        String jsonPayload = mapper.writeValueAsString(cerebrasPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cerebrasApiUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cerebrasApiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            try {
                JsonNode cerebrasResponse = mapper.readTree(response.body());
                JsonNode choice = cerebrasResponse.path("choices").get(0);
                if (choice == null) {
                    throw new Exception("Missing 'choices' array");
                }
                String text = choice.path("message").path("content").asText(null);
                if (text == null || text.trim().isEmpty()) {
                    throw new Exception("No response from Cerebras AI (" + model + ").");
                }
                return text;
            } catch (Exception e) {
                throw new Exception("Unexpected 200 response from Cerebras AI (" + model + "). Body: " + response.body(), e);
            }
        } else {
            if (isCerebrasContextExceeded(response)) {
                throw new AIContextExceededException(
                        "Cerebras AI (" + model + ") context exceeded.");
            }
            throw new Exception("Failed to get response from Cerebras AI (" + model + "). Status: "
                    + response.statusCode() + ", Body: " + response.body());
        }
    }

    private boolean isCerebrasContextExceeded(HttpResponse<String> response) {
        if (response == null || response.body() == null) {
            return false;
        }

        String body = response.body();
        if (body.contains("\"code\":\"token_quota_exceeded\"")
                || body.contains("\"type\":\"too_many_tokens_error\"")
                || body.contains("Tokens per minute limit exceeded")) {
            return true;
        }

        try {
            JsonNode root = mapper.readTree(body);
            String code = root.path("code").asText("");
            String type = root.path("type").asText("");
            String message = root.path("message").asText("");
            return "token_quota_exceeded".equals(code)
                    || "too_many_tokens_error".equals(type)
                    || message.contains("Tokens per minute limit exceeded");
        } catch (Exception ignored) {
            return false;
        }
    }

    protected String getRandomModel(List<String> models) {
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

    protected String buildPrompt(String question, List<String> logs, String promptPrefix) {
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
            if (Prompts.DEBUGAI_PREFIX.equals(promptPrefix)) {
                return question + "\n\n" + logsStr;
            }
            return Prompts.QUESTION_PREFIX + question + Prompts.QUESTION_SUFFIX + logsStr;
        } else {
            return promptPrefix + logsStr;
        }
    }

    private List<Map<String, String>> buildMessages(String prompt, boolean skipSystem) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (!skipSystem) {
            messages.add(Map.of("role", "system", "content", Prompts.SYSTEM_OVERVIEW));
        }
        messages.add(Map.of("role", "user", "content", prompt));
        return messages;
    }

    protected String trimReasoning(String r) {
        if (r == null || r.isEmpty()) return "";
        String[] lines = r.split("\n");
        List<Integer> stepIndices = new ArrayList<>();
        // Look for main list items starting at the beginning of the line
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches("^(\\d+\\.|\\*).*")) {
                stepIndices.add(i);
            }
        }
        
        int lastStepIdx = -1;
        if (!stepIndices.isEmpty()) {
            lastStepIdx = stepIndices.get(stepIndices.size() - 1);
        }
        
        // Strict limits: 15 lines, 2500 characters
        int maxLines = 15;
        int maxChars = 2500;
        
        StringBuilder sb = new StringBuilder();
        int lineCount = 0;
        // Build from the end (tail) upwards
        for (int i = lines.length - 1; i >= 0 && lineCount < maxLines; i--) {
            // Check character limit before appending
            if (sb.length() + lines[i].length() + 1 > maxChars) break;
            
            sb.insert(0, lines[i] + "\n");
            lineCount++;
            
            // If we've reached the start of the last identified step, stop here to avoid
            // including content from previous steps, UNLESS we must keep going to fill limits
            // (but user said "only the last line item", so stopping at lastStepIdx is correct).
            if (i == lastStepIdx) break;
        }
        
        return sb.toString().trim();
    }

    private String buildStreamingOutput(StringBuilder reasoning, StringBuilder responseContent, String footer,
            String[] clockFaces, int updateCount, long startTime) {
        StringBuilder streamingOutput = new StringBuilder();
        if (reasoning.length() > 0) {
            String r = trimReasoning(reasoning.toString());
            streamingOutput.append("> ").append(r.replace("\n", "\n> ")).append("\n\n");
        }
        if (responseContent.length() > 0) {
            streamingOutput.append(responseContent);
        }

        String output = streamingOutput.toString();
        if (output.length() > 16000) {
            output = output.substring(0, 15900) + "... [TRUNCATED]";
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        long elapsedSec = elapsedMs / 1000;
        String elapsedStr = elapsedSec < 60 ? (elapsedSec + "s") : ((elapsedSec / 60) + "m" + (elapsedSec % 60) + "s");
        String indicator = clockFaces[updateCount % clockFaces.length] + " " + elapsedStr;

        StringBuilder rendered = new StringBuilder(output);
        rendered.append("\n\n").append(indicator);
        if (footer != null && !footer.isEmpty()) {
            rendered.append("\n").append(footer);
        }
        return rendered.toString();
    }

    protected String appendMessageLink(String aiAnswer, String exportRoomId, String firstEventId) {
        if (firstEventId != null) {
            String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + firstEventId;
            return aiAnswer + "\n\n" + messageLink;
        }
        return aiAnswer;
    }

    protected boolean isContextExceededMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return false;
        }

        String lower = errorMessage.toLowerCase();
        return lower.contains("exceeded the maximum context length")
                || lower.contains("context exceeded")
                || lower.contains("token_quota_exceeded")
                || lower.contains("too_many_tokens_error")
                || lower.contains("tokens per minute limit exceeded");
    }

    protected String extractContextInfo(String errorMessage) {
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
