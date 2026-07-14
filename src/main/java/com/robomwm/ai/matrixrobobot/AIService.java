package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public class AIService {
    protected static final long STATUS_UPDATE_INTERVAL_MS = 5000; // 5 seconds
    protected final HttpClient client;
    protected final ObjectMapper mapper;
    protected final String homeserver;
    protected final String accessToken;
    protected final String arliApiKey;
    protected final String cerebrasApiKey;
    protected final String groqApiKey;
    protected final String openrouterApiKey;
    protected final String freeLlmApiKey;
    protected final String ollamaProxyApiKey;
    protected final String ollamaProxyUrl;
    protected final RoomHistoryManager historyManager;
    protected final Random random;
    public static final int AI_TIMEOUT_SECONDS = 1200;
    protected final List<String> arliModels;
    protected final List<String> cerebrasModels;
    protected final List<String> groqModels;
    protected final List<String> openrouterModels;
    protected final List<String> freeLlmModels;
    protected final List<String> ollamaProxyModels;

    public AIService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken, String arliApiKey,
            String cerebrasApiKey, String groqApiKey, String openrouterApiKey, String freeLlmApiKey,
            String ollamaProxyApiKey, String ollamaProxyUrl,
            List<String> arliModels, List<String> cerebrasModels, List<String> groqModels, List<String> openrouterModels, 
            List<String> freeLlmModels, List<String> ollamaProxyModels) {
        this.client = client;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.arliApiKey = arliApiKey;
        this.cerebrasApiKey = cerebrasApiKey;
        this.groqApiKey = groqApiKey;
        this.openrouterApiKey = openrouterApiKey;
        this.freeLlmApiKey = freeLlmApiKey;
        this.ollamaProxyApiKey = ollamaProxyApiKey;
        this.ollamaProxyUrl = ollamaProxyUrl != null ? ollamaProxyUrl : "http://localhost:8000/api/chat";
        this.arliModels = arliModels != null && !arliModels.isEmpty() ? arliModels : Arrays.asList("Qwen3.5-27B-Derestricted");
        this.cerebrasModels = cerebrasModels != null && !cerebrasModels.isEmpty() ? cerebrasModels : Arrays.asList("qwen-3-235b-a22b-instruct-2507");
        this.groqModels = groqModels != null && !groqModels.isEmpty() ? groqModels : Arrays.asList("meta-llama/llama-4-scout-17b-16e-instruct");
        this.openrouterModels = openrouterModels != null && !openrouterModels.isEmpty() ? openrouterModels : Arrays.asList("openrouter/free");
        this.freeLlmModels = freeLlmModels != null && !freeLlmModels.isEmpty() ? freeLlmModels : Arrays.asList("auto");
        this.ollamaProxyModels = ollamaProxyModels != null && !ollamaProxyModels.isEmpty() ? ollamaProxyModels : Arrays.asList("llama3.2:3b");
        this.historyManager = new RoomHistoryManager(client, mapper, homeserver, accessToken);
        this.random = new Random();
    }

    // Cache for OpenRouter ZDR endpoint names (simple in-memory cache)
    private volatile java.util.Set<String> openrouterZdrSet = null;
    private volatile long openrouterZdrFetchedAt = 0L; // epoch ms
    private static final long OPENROUTER_ZDR_TTL_MS = 86400 * 60 * 1000; // 1 day

    private java.util.Set<String> getOpenrouterZdrSet() throws Exception {
        long now = System.currentTimeMillis();
        if (openrouterZdrSet != null && (now - openrouterZdrFetchedAt) < OPENROUTER_ZDR_TTL_MS) {
            return openrouterZdrSet;
        }
        if (openrouterApiKey == null || openrouterApiKey.isEmpty()) {
            throw new Exception("OPENROUTER_API_KEY not configured");
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/endpoints/zdr"))
                .header("Authorization", "Bearer " + openrouterApiKey)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new Exception("OpenRouter ZDR fetch failed. Status: " + resp.statusCode() + ", Body: " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        java.util.Set<String> names = new java.util.HashSet<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode n : data) {
                String name = n.path("name").asText(null);
                if (name != null) names.add(name);
            }
        }
        openrouterZdrSet = names;
        openrouterZdrFetchedAt = now;
        return openrouterZdrSet;
    }

    public enum Backend {
        AUTO, ARLIAI, OLLAMA_PROXY, FREELLM, CEREBRAS, GROQ, OPENROUTER
    }

    public static void applyArliAiNonThinkingDefaults(Map<String, Object> payload) {
        payload.putIfAbsent("temperature", 0.7);
        payload.putIfAbsent("top_p", 0.8);
        payload.putIfAbsent("top_k", 20);
        payload.putIfAbsent("min_p", 0.0);
        payload.putIfAbsent("presence_penalty", 1.5);
        payload.putIfAbsent("repetition_penalty", 1.0);
        payload.put("chat_template_kwargs", Map.of("enable_thinking", false));
    }

    private static class ProviderConfig {
        final Backend backend;
        final String displayName;
        final String noticeName;
        final String apiKeyName;
        final String apiKey;
        final String url;
        final boolean stream;
        final Map<String, String> extraHeaders;
        final Map<String, Object> extraPayload;

        ProviderConfig(Backend backend, String displayName, String noticeName, String apiKeyName, String apiKey,
                String url, boolean stream, Map<String, String> extraHeaders, Map<String, Object> extraPayload) {
            this.backend = backend;
            this.displayName = displayName;
            this.noticeName = noticeName;
            this.apiKeyName = apiKeyName;
            this.apiKey = apiKey;
            this.url = url;
            this.stream = stream;
            this.extraHeaders = extraHeaders;
            this.extraPayload = extraPayload;
        }
    }

    private static class ProviderAttempt {
        final ProviderConfig provider;
        final String model;

        ProviderAttempt(ProviderConfig provider, String model) {
            this.provider = provider;
            this.model = model;
        }
    }

    public static class Prompts {
        public static final String SYSTEM_OVERVIEW = "You provide a concise, high level overview of a chat log.";
        public static final String SYSTEM_ASK = "You answer the user's question using the provided chat logs as your primary source of information. Do not use tables; table markdown is not supported.";
        public static final String QUESTION_PREFIX = "'";
        public static final String QUESTION_SUFFIX = "' Answer this prompt using these chat logs:\n\n";
        public static final String ASK_PREFIX = "";
        public static final String OVERVIEW_PREFIX = "Give a concise, high level overview of the following chat logs. No  complete sentences. Use only a title and timestamp for each topic; include as bullet points one chat message verbatim (or more, only if necessary) with username for each topic. No table format. Then summarize with bullet points all of the topics at end in caveman style. This is caveman style: Terse like caveman. Only fluff die. Drop: articles, filler (just/really/basically), pleasantries, hedging. Fragments OK. Short synonyms.\n\n";
        public static final String SUMMARY_PREFIX = "Give a concise, high level overview (no analysis) of the following chat logs. No complete sentences. Make use of bullet points of key topics with timestamp; include zero or more chat messages verbatim (with username) as sub-bullets. Bias including discovered technical solutions or resources, and philosophical discussions. Do not exceed 30 seconds of reading time.\n\n";
        public static final String TLDR_PREFIX = "Provide a very concise summary of the following chat logs that can be read in 15 seconds or less. Make use of bullet points of key topics with timestamp; be extremely brief, no complete sentences. Always include philosophical or technical topics that are informative towards a discovered solution or resources. Then directly include the best chat message verbatim; have bias towards one that is informative towards a discovered solution or informative resource. After printing that, in a new section, summarize the chat log into a short list of discussion topics. Group the messages into topics and output ONE bullet per topic in the exact form:\n- <short topic description (<N> messages)\nOrder topics from most to fewest messages. The message counts must add up to the total number of messages. Output ONLY the topic lines, no header, no extra text.\n\n";
        public static final String DEBUGAI_PREFIX = "\n\n";
        public static final String TOPICLIST_PREFIX = "You summarize a chat log into a short list of discussion topics. Group the messages into topics and output ONE bullet per topic in the exact form:\n- <short topic description (<N> messages)\nOrder topics from most to fewest messages. The message counts must add up to the total number of messages. Output ONLY the topic lines, no header, no extra text.\n\n";
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

            performAIQuery(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag, preferredBackend, null, AI_TIMEOUT_SECONDS, statusEventId, null);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdownNotice(responseRoomId, "Error querying AI: " + e.getMessage());
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
                                Backend preferredBackend, String forcedModel, int timeoutSeconds, String statusEventId, String footer) {
        if (abortFlag != null && abortFlag.get()) return;
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);

        boolean skipSystem = Prompts.DEBUGAI_PREFIX.equals(promptPrefix);
        boolean isAsk = Prompts.ASK_PREFIX.equals(promptPrefix);
        String prompt = buildPrompt(question, history.logs, promptPrefix);
        List<ProviderAttempt> attempts = buildProviderAttempts(preferredBackend, forcedModel);

        // Track batched status updates to reduce Matrix message spam
        String batchEventId = null;
        StringBuilder batchStatus = new StringBuilder();
        StringBuilder accumulatedStatus = new StringBuilder();
        Instant lastUpdateTime = null;
        boolean batching = attempts.size() > 1; // Only batch if multiple attempts possible

        for (int i = 0; i < attempts.size(); i++) {
            if (abortFlag != null && abortFlag.get()) return;

            ProviderAttempt attempt = attempts.get(i);
            ProviderConfig provider = attempt.provider;
            
            try {
                // For first attempt or non-batching mode, send immediate status
                if (i == 0 || !batching) {
                    String initialStatus = "Querying " + provider.noticeName + " (" + attempt.model + ")...";
                    batchEventId = matrixClient.sendNoticeWithEventId(responseRoomId, initialStatus);
                    lastUpdateTime = Instant.now();
                    batchStatus.setLength(0);
                    accumulatedStatus.setLength(0);
                    accumulatedStatus.append(initialStatus);
                }
                
                if (provider.stream) {
                    callStreamingToEvent(provider, prompt, attempt.model, skipSystem, isAsk, responseRoomId,
                            new String[]{batchEventId != null ? batchEventId : ""}, footer, timeoutSeconds, abortFlag, true, exportRoomId,
                            history.firstEventId);
                } else {
                    String answer = callNonStreaming(provider, prompt, attempt.model, skipSystem, isAsk, timeoutSeconds);
                    matrixClient.updateMarkdownMessage(responseRoomId, batchEventId,
                            appendMessageLink(answer, exportRoomId, history.firstEventId, provider.displayName,
                                    attempt.model));
                }
                // Success - flush any batched status immediately
                if (batching && batchStatus.length() > 0) {
                    sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), true);
                    batchStatus.setLength(0);
                }
                return;
            } catch (Exception e) {
                String errorMsg = e.getMessage() == null ? e.toString() : e.getMessage();
                String errorPrefix = (footer != null ? footer + ": " : "");
                System.out.println(errorPrefix + provider.displayName + " (" + attempt.model + ") failed: " + errorMsg);
                
                String failureLine = provider.noticeName + " (" + attempt.model + ") failed: " + errorMsg;
                String statusUpdate = appendStatusLine(accumulatedStatus.toString(), errorPrefix + failureLine);
                accumulatedStatus.setLength(0);
                accumulatedStatus.append(statusUpdate);
                
                // Batch failures for grouped updates
                if (batching) {
                    batchStatus.setLength(0);
                    batchStatus.append(statusUpdate);
                    
                    // Flush batch if: last attempt, or enough time passed, or enough failures
                    Instant now = Instant.now();
                    if (i == attempts.size() - 1 || 
                        (lastUpdateTime != null && Duration.between(lastUpdateTime, now).toMillis() >= STATUS_UPDATE_INTERVAL_MS) ||
                        batchStatus.length() >= 500) { // Reasonable max length
                        sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), false);
                        lastUpdateTime = now;
                    }
                } else {
                    // Non-batching mode: update immediately
                    if (batchEventId != null) {
                        matrixClient.updateNoticeMessage(responseRoomId, batchEventId, statusUpdate);
                    }
                }
                
// Always allow fallback for OLLAMA_PROXY since it's not 24/7
                // Otherwise only fallback in AUTO mode and if not the last attempt
                if ((preferredBackend != Backend.AUTO && provider.backend != Backend.OLLAMA_PROXY) || i == attempts.size() - 1) {
                    if (i == attempts.size() - 1 && !history.antispamApplied) {
                        // All providers failed, try removing messages from specific spammy user first
                        System.out.println("All providers failed, retrying with specific user filtering...");
                        matrixClient.updateNoticeMessage(responseRoomId, batchEventId, 
                                "All providers failed. Removing spammer messages from specific spammy user and retrying...");
                        
                        // Create filtered history removing messages from the specific user
                        RoomHistoryManager.ChatLogsResult filteredHistory = new RoomHistoryManager.ChatLogsResult(
                                filterUserMessages(history.logs, "@buynbadrah:mikuplushfarm.ovh"), history.firstEventId, history.errorMessage, false);
                        
                        // Retry with specific user filtering
                        performAIQueryWithUserFilter(responseRoomId, exportRoomId, filteredHistory, question, promptPrefix, 
                                abortFlag, preferredBackend, forcedModel, timeoutSeconds, batchEventId, footer);
                        return;
                    }
                    
                    handleFinalError(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag,
                            preferredBackend, forcedModel, timeoutSeconds, batchEventId,
                            errorPrefix + provider.displayName + " (" + attempt.model + ") failed: " + errorMsg);
                    // Flush any remaining batched status on final error
                    if (batching && batchStatus.length() > 0) {
                        sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), false);
                        batchStatus.setLength(0);
                    }
                    return;
                }
            }
        }

    }

    private void performAIQueryWithUserFilter(String responseRoomId, String exportRoomId, RoomHistoryManager.ChatLogsResult history,
            String question, String promptPrefix, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            Backend preferredBackend, String forcedModel, int timeoutSeconds, String statusEventId, 
            String footer) {
        if (abortFlag != null && abortFlag.get()) return;
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);

        boolean skipSystem = Prompts.DEBUGAI_PREFIX.equals(promptPrefix);
        boolean isAsk = Prompts.ASK_PREFIX.equals(promptPrefix);
        
        String prompt = buildPrompt(question, history.logs, promptPrefix);
        List<ProviderAttempt> attempts = buildProviderAttempts(preferredBackend, forcedModel);

        // Track batched status updates to reduce Matrix message spam
        String batchEventId = null;
        StringBuilder batchStatus = new StringBuilder();
        StringBuilder accumulatedStatus = new StringBuilder();
        Instant lastUpdateTime = null;
        boolean batching = attempts.size() > 1; // Only batch if multiple attempts possible

        for (int i = 0; i < attempts.size(); i++) {
            if (abortFlag != null && abortFlag.get()) return;

            ProviderAttempt attempt = attempts.get(i);
            ProviderConfig provider = attempt.provider;
            
            try {
                // For first attempt or non-batching mode, send immediate status
                if (i == 0 || !batching) {
                    String initialStatus = "Querying " + provider.noticeName + " (" + attempt.model + ")... (filtered user)";
                    batchEventId = matrixClient.sendNoticeWithEventId(responseRoomId, initialStatus);
                    lastUpdateTime = Instant.now();
                    batchStatus.setLength(0);
                    accumulatedStatus.setLength(0);
                    accumulatedStatus.append(initialStatus);
                }
                
                if (provider.stream) {
                    callStreamingToEvent(provider, prompt, attempt.model, skipSystem, isAsk, responseRoomId,
                            new String[]{batchEventId != null ? batchEventId : ""}, footer, timeoutSeconds, abortFlag, true, exportRoomId,
                            history.firstEventId);
                } else {
                    String answer = callNonStreaming(provider, prompt, attempt.model, skipSystem, isAsk, timeoutSeconds);
                    matrixClient.updateMarkdownMessage(responseRoomId, batchEventId,
                            appendMessageLink(answer, exportRoomId, history.firstEventId, provider.displayName,
                                    attempt.model));
                }
                // Success - flush any batched status immediately
                if (batching && batchStatus.length() > 0) {
                    sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), true);
                    batchStatus.setLength(0);
                }
                return;
            } catch (Exception e) {
                String errorMsg = e.getMessage() == null ? e.toString() : e.getMessage();
                String errorPrefix = (footer != null ? footer + ": " : "");
                System.out.println(errorPrefix + provider.displayName + " (" + attempt.model + ") failed: " + errorMsg);
                
                String failureLine = provider.noticeName + " (" + attempt.model + ") failed: " + errorMsg;
                String statusUpdate = appendStatusLine(accumulatedStatus.toString(), errorPrefix + failureLine);
                accumulatedStatus.setLength(0);
                accumulatedStatus.append(statusUpdate);
                
                // Batch failures for grouped updates
                if (batching) {
                    batchStatus.setLength(0);
                    batchStatus.append(statusUpdate);
                    
                    // Flush batch if: last attempt, or enough time passed, or enough failures
                    Instant now = Instant.now();
                    if (i == attempts.size() - 1 || 
                        (lastUpdateTime != null && Duration.between(lastUpdateTime, now).toMillis() >= STATUS_UPDATE_INTERVAL_MS) ||
                        batchStatus.length() >= 500) { // Reasonable max length
                        sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), false);
                        lastUpdateTime = now;
                    }
                } else {
                    // Non-batching mode: update immediately
                    if (batchEventId != null) {
                        matrixClient.updateNoticeMessage(responseRoomId, batchEventId, statusUpdate);
                    }
                }
                
                // Always allow fallback for OLLAMA_PROXY since it's not 24/7
                // Otherwise only fallback in AUTO mode and if not the last attempt
                if ((preferredBackend != Backend.AUTO && provider.backend != Backend.OLLAMA_PROXY) || i == attempts.size() - 1) {
                    if (i == attempts.size() - 1) {
                        // All providers failed after user filtering, try with antispam filtering
                        System.out.println("All providers failed after user filtering, retrying with antispam filtering...");
                        matrixClient.updateNoticeMessage(responseRoomId, batchEventId, 
                                "All providers failed. Applying antispam filtering and retrying...");
                        
                        // Create filtered history - antispam will be applied by performAIQueryWithAntispam
                        RoomHistoryManager.ChatLogsResult filteredHistory = new RoomHistoryManager.ChatLogsResult(
                                history.logs, history.firstEventId, history.errorMessage, false);
                        
                        // Retry with antispam filtering
                        performAIQueryWithAntispam(responseRoomId, exportRoomId, filteredHistory, question, promptPrefix, 
                                abortFlag, preferredBackend, forcedModel, timeoutSeconds, batchEventId, footer);
                        return;
                    }
                    
                    handleFinalError(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag,
                            preferredBackend, forcedModel, timeoutSeconds, batchEventId,
                            errorPrefix + provider.displayName + " (" + attempt.model + ") failed: " + errorMsg);
                    // Flush any remaining batched status on final error
                    if (batching && batchStatus.length() > 0) {
                        sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), false);
                        batchStatus.setLength(0);
                    }
                    return;
                }
            }
        }

    }

    private void performAIQueryWithAntispam(String responseRoomId, String exportRoomId, RoomHistoryManager.ChatLogsResult history,
                                        String question, String promptPrefix, java.util.concurrent.atomic.AtomicBoolean abortFlag,
                                        Backend preferredBackend, String forcedModel, int timeoutSeconds, String statusEventId, 
                                        String footer) {
        if (abortFlag != null && abortFlag.get()) return;
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);

        boolean skipSystem = Prompts.DEBUGAI_PREFIX.equals(promptPrefix);
        boolean isAsk = Prompts.ASK_PREFIX.equals(promptPrefix);
        
        // Apply antispam filtering to logs if not already applied
        List<String> filteredLogs;
        if (history.antispamApplied) {
            filteredLogs = history.logs;
        } else {
            filteredLogs = AntispamFilter.applyAllFilters(history.logs);
        }
        RoomHistoryManager.ChatLogsResult filteredHistory = new RoomHistoryManager.ChatLogsResult(
                filteredLogs, history.firstEventId, history.errorMessage, true);
        
        String prompt = buildPrompt(question, filteredLogs, promptPrefix);
        List<ProviderAttempt> attempts = buildProviderAttempts(preferredBackend, forcedModel);

        // Track batched status updates to reduce Matrix message spam
        String batchEventId = null;
        StringBuilder batchStatus = new StringBuilder();
        StringBuilder accumulatedStatus = new StringBuilder();
        Instant lastUpdateTime = null;
        boolean batching = attempts.size() > 1; // Only batch if multiple attempts possible

        for (int i = 0; i < attempts.size(); i++) {
            if (abortFlag != null && abortFlag.get()) return;

            ProviderAttempt attempt = attempts.get(i);
            ProviderConfig provider = attempt.provider;
            
            try {
                // For first attempt or non-batching mode, send immediate status
                if (i == 0 || !batching) {
                    String initialStatus = "Querying " + provider.noticeName + " (" + attempt.model + ")... (with antispam filtering)";
                    batchEventId = matrixClient.sendNoticeWithEventId(responseRoomId, initialStatus);
                    lastUpdateTime = Instant.now();
                    batchStatus.setLength(0);
                    accumulatedStatus.setLength(0);
                    accumulatedStatus.append(initialStatus);
                }
                
                String[] outputEventIdHolder = new String[]{null};
                if (provider.stream) {
                    callStreamingToEvent(provider, prompt, attempt.model, skipSystem, isAsk, responseRoomId,
                            outputEventIdHolder, footer, timeoutSeconds, abortFlag, true, exportRoomId,
                            filteredHistory.firstEventId);
                } else {
                    String answer = callNonStreaming(provider, prompt, attempt.model, skipSystem, isAsk, timeoutSeconds);
                    String renderedAnswer = appendMessageLink(answer, exportRoomId, filteredHistory.firstEventId,
                            provider.displayName, attempt.model);
                    matrixClient.sendMarkdownNoticeWithEventId(responseRoomId, renderedAnswer);
                }
                // Success - flush any batched status immediately
                if (batching && batchStatus.length() > 0) {
                    sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), true);
                    batchStatus.setLength(0);
                }
                return;
            } catch (Exception e) {
                String errorMsg = e.getMessage() == null ? e.toString() : e.getMessage();
                String errorPrefix = (footer != null ? footer + ": " : "");
                System.out.println(errorPrefix + provider.displayName + " (" + attempt.model + ") failed: " + errorMsg);
                
                String failureLine = provider.noticeName + " (" + attempt.model + ") failed: " + errorMsg;
                String statusUpdate = appendStatusLine(accumulatedStatus.toString(), errorPrefix + failureLine);
                accumulatedStatus.setLength(0);
                accumulatedStatus.append(statusUpdate);
                
                // Batch failures for grouped updates
                if (batching) {
                    batchStatus.setLength(0);
                    batchStatus.append(statusUpdate);
                    
                    // Flush batch if: last attempt, or enough time passed, or enough failures
                    Instant now = Instant.now();
                    if (i == attempts.size() - 1 || 
                        (lastUpdateTime != null && Duration.between(lastUpdateTime, now).toMillis() >= STATUS_UPDATE_INTERVAL_MS) ||
                        batchStatus.length() >= 500) { // Reasonable max length
                        sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), false);
                        lastUpdateTime = now;
                    }
                } else {
                    // Non-batching mode: update immediately
                    if (batchEventId != null) {
                        matrixClient.updateNoticeMessage(responseRoomId, batchEventId, statusUpdate);
                    }
                }
                
                // For antispam retry, if any provider fails, we consider it final since we already applied filtering
                if (i == attempts.size() - 1) {
                    handleFinalError(responseRoomId, exportRoomId, filteredHistory, question, promptPrefix, abortFlag,
                            preferredBackend, forcedModel, timeoutSeconds, batchEventId,
                            errorPrefix + provider.displayName + " (" + attempt.model + ") failed: " + errorMsg);
                    // Flush any remaining batched status on final error
                    if (batching && batchStatus.length() > 0) {
                        sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), false);
                        batchStatus.setLength(0);
                    }
                    return;
                }
                
                // Always allow fallback for OLLAMA_PROXY since it's not 24/7
                if (preferredBackend != Backend.AUTO && provider.backend != Backend.OLLAMA_PROXY) {
                    handleFinalError(responseRoomId, exportRoomId, filteredHistory, question, promptPrefix, abortFlag,
                            preferredBackend, forcedModel, timeoutSeconds, batchEventId,
                            errorPrefix + provider.displayName + " (" + attempt.model + ") failed: " + errorMsg);
                    // Flush any remaining batched status on final error
                    if (batching && batchStatus.length() > 0) {
                        sendBatchedUpdate(matrixClient, responseRoomId, batchEventId, batchStatus.toString(), false);
                        batchStatus.setLength(0);
                    }
                    return;
                }
            }
        }

    }

    static String appendStatusLine(String existingStatus, String newLine) {
        if (newLine == null || newLine.isBlank()) {
            return existingStatus == null ? "" : existingStatus;
        }
        if (existingStatus == null || existingStatus.isBlank()) {
            return newLine;
        }
        return existingStatus + "\n" + newLine;
    }

    private void sendBatchedUpdate(MatrixClient matrixClient, String roomId, String eventId, String status, boolean success) {
        if (eventId == null || eventId.isEmpty()) return;
        if (success) {
            // On success, we already updated with the answer, just ensure batch is cleared
            return;
        }
        matrixClient.updateNoticeMessage(roomId, eventId, status);
    }

    private List<ProviderAttempt> buildProviderAttempts(Backend preferredBackend, String forcedModel) {
        List<ProviderAttempt> attempts = new ArrayList<>();
        Backend[] order = {Backend.ARLIAI, Backend.OLLAMA_PROXY, Backend.FREELLM, Backend.CEREBRAS, Backend.GROQ, Backend.OPENROUTER};
        for (Backend backend : order) {
            if (preferredBackend != Backend.AUTO && preferredBackend != backend) {
                continue;
            }

            ProviderConfig provider = getProviderConfig(backend);
            if (provider == null || provider.apiKey == null || provider.apiKey.isEmpty()) {
                continue;
            }

            if (preferredBackend == backend && forcedModel != null) {
                attempts.add(new ProviderAttempt(provider, forcedModel));
                continue;
            }

            if (backend == Backend.CEREBRAS) {
                for (String model : cerebrasModels) {
                    attempts.add(new ProviderAttempt(provider, model));
                }
            } else if (backend == Backend.GROQ) {
                for (String model : groqModels) {
                    attempts.add(new ProviderAttempt(provider, model));
                }
            } else if (backend == Backend.ARLIAI) {
                attempts.add(new ProviderAttempt(provider, getRandomModel(arliModels)));
            } else if (backend == Backend.OPENROUTER) {
                // For OpenRouter, prefer sending full models array so OpenRouter can use native fallback.
                // Use a joined string for display, actual request will use models list.
                String displayModel = String.join(",", openrouterModels);
                attempts.add(new ProviderAttempt(provider, displayModel));
            } else if (backend == Backend.FREELLM) {
                for (String model : freeLlmModels) {
                    attempts.add(new ProviderAttempt(provider, model));
                }
            } else if (backend == Backend.OLLAMA_PROXY) {
                for (String model : ollamaProxyModels) {
                    attempts.add(new ProviderAttempt(provider, model));
                }
            }
        }
        return attempts;
    }

    private ProviderConfig getProviderConfig(Backend backend) {
        switch (backend) {
            case CEREBRAS:
                return new ProviderConfig(Backend.CEREBRAS, "Cerebras", "Cerebras", "CEREBRAS_API_KEY",
                        cerebrasApiKey, "https://api.cerebras.ai/v1/chat/completions", false, Map.of(), Map.of());
            case GROQ:
                return new ProviderConfig(Backend.GROQ, "Groq", "Groq", "GROQ_API_KEY", groqApiKey,
                        "https://api.groq.com/openai/v1/chat/completions", true, Map.of(), Map.of());
            case OPENROUTER:
                return new ProviderConfig(Backend.OPENROUTER, "OpenRouter", "OpenRouter", "OPENROUTER_API_KEY",
                        openrouterApiKey, "https://openrouter.ai/api/v1/chat/completions", true,
                        Map.of(
                                "HTTP-Referer", "https://github.com/MLG-SERBUR/matrix-robobot",
                                "X-Title", "Matrix Robobot"),
                        Map.of());
            case ARLIAI:
                return new ProviderConfig(Backend.ARLIAI, "ArliAI", "Arli AI", "ARLI_API_KEY", arliApiKey,
                        "https://api.arliai.com/v1/chat/completions", true, Map.of(),
                        Map.of("output_kind", "delta"));
            case FREELLM:
                return new ProviderConfig(Backend.FREELLM, "FreeLLM", "FreeLLM", "FREELLM_API_KEY",
                        freeLlmApiKey, "http://localhost:3001/v1/chat/completions", true, Map.of(), Map.of());
            case OLLAMA_PROXY:
                return new ProviderConfig(Backend.OLLAMA_PROXY, "OllamaProxy", "Ollama Proxy", "OLLAMA_PROXY_API_KEY",
                        ollamaProxyApiKey, ollamaProxyUrl, true, Map.of(), Map.of());
            default:
                return null;
        }
    }

    private String selectModel(Backend backend, Backend preferredBackend, String forcedModel) {
        // This is no longer used by buildProviderAttempts, but keeping for compatibility if needed.
        if (preferredBackend == backend && forcedModel != null) {
            return forcedModel;
        }

        switch (backend) {
            case CEREBRAS:
                return cerebrasModels.get(0);
            case GROQ:
                return groqModels.get(0);
            case OPENROUTER:
                return openrouterModels.get(0);
            case ARLIAI:
                return getRandomModel(arliModels);
            case FREELLM:
                return freeLlmModels.get(0);
            case OLLAMA_PROXY:
                return ollamaProxyModels.get(0);
            default:
                return "unknown-model";
        }
    }

    
    protected String callOpenRouterStreamingToEvent(String prompt, String model, boolean skipSystem, String responseRoomId,
            String[] eventIdHolder, String footer, int timeoutSeconds, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            boolean useNotice, String exportRoomId, String firstEventId) throws Exception {
        return callStreamingToEvent(getProviderConfig(Backend.OPENROUTER), prompt, model, skipSystem, false, responseRoomId,
                eventIdHolder, footer, timeoutSeconds, abortFlag, useNotice, exportRoomId, firstEventId);
    }



    protected void handleFinalError(String responseRoomId, String exportRoomId, RoomHistoryManager.ChatLogsResult history,
                                    String question, String promptPrefix, java.util.concurrent.atomic.AtomicBoolean abortFlag,
                                    Backend preferredBackend, String forcedModel, int timeoutSeconds, String statusEventId, String errorMsg) {
    }



    protected String callGroq(String prompt, String model, boolean skipSystem, String responseRoomId, String exportRoomId, String firstEventId, int timeoutSeconds, java.util.concurrent.atomic.AtomicBoolean abortFlag, String footer) throws Exception {
        return callStreaming(getProviderConfig(Backend.GROQ), prompt, model, skipSystem, false, responseRoomId, exportRoomId,
                firstEventId, timeoutSeconds, abortFlag, footer);
    }

    protected String callGroqStreamingToEvent(String prompt, String model, boolean skipSystem, String responseRoomId,
            String[] eventIdHolder, String footer, int timeoutSeconds, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            boolean useNotice, String exportRoomId, String firstEventId) throws Exception {
        return callStreamingToEvent(getProviderConfig(Backend.GROQ), prompt, model, skipSystem, false, responseRoomId,
                eventIdHolder, footer, timeoutSeconds, abortFlag, useNotice, exportRoomId, firstEventId);
    }

    protected String callArliAI(String prompt, String model, boolean skipSystem, String responseRoomId, String exportRoomId, String firstEventId, int timeoutSeconds, java.util.concurrent.atomic.AtomicBoolean abortFlag, String footer) throws Exception {
        return callStreaming(getProviderConfig(Backend.ARLIAI), prompt, model, skipSystem, false, responseRoomId, exportRoomId,
                firstEventId, timeoutSeconds, abortFlag, footer);
    }

    protected String callArliAIStreamingToEvent(String prompt, String model, boolean skipSystem, String responseRoomId,
            String[] eventIdHolder, String footer, int timeoutSeconds, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            boolean useNotice, String exportRoomId, String firstEventId) throws Exception {
        return callStreamingToEvent(getProviderConfig(Backend.ARLIAI), prompt, model, skipSystem, false, responseRoomId,
                eventIdHolder, footer, timeoutSeconds, abortFlag, useNotice, exportRoomId, firstEventId);
    }

    private String callStreaming(ProviderConfig provider, String prompt, String model, boolean skipSystem,
            boolean isAsk, String responseRoomId, String exportRoomId, String firstEventId, int timeoutSeconds,
            java.util.concurrent.atomic.AtomicBoolean abortFlag, String footer) throws Exception {
        HttpRequest request = buildChatCompletionRequest(provider, prompt, model, skipSystem, isAsk, true, timeoutSeconds);
        return AIRequestQueue.run(provider.displayName + " (" + model + ") streaming",
                () -> streamArliAIResponse(request, responseRoomId, exportRoomId, firstEventId, provider.displayName,
                        abortFlag, footer));
    }

    private String callStreamingToEvent(ProviderConfig provider, String prompt, String model, boolean skipSystem,
            boolean isAsk, String responseRoomId, String[] eventIdHolder, String footer, int timeoutSeconds,
            java.util.concurrent.atomic.AtomicBoolean abortFlag, boolean useNotice, String exportRoomId,
            String firstEventId) throws Exception {
        HttpRequest request = buildChatCompletionRequest(provider, prompt, model, skipSystem, isAsk, true, timeoutSeconds);
        return AIRequestQueue.run(provider.displayName + " (" + model + ") streaming",
                () -> streamArliAIResponseToEvent(request, responseRoomId, eventIdHolder, provider.displayName,
                        abortFlag, footer, useNotice, exportRoomId, firstEventId));
    }


    protected String streamArliAIResponse(HttpRequest request, String responseRoomId, String exportRoomId, String firstEventId, String aiName, java.util.concurrent.atomic.AtomicBoolean abortFlag, String footer) throws Exception {
        String[] eventIdHolder = new String[]{null};
        return streamArliAIResponseToEvent(request, responseRoomId, eventIdHolder, aiName, abortFlag, footer, true,
                exportRoomId, firstEventId);
    }

    protected String streamArliAIResponseToEvent(HttpRequest request, String responseRoomId, String[] eventIdHolder,
            String aiName, java.util.concurrent.atomic.AtomicBoolean abortFlag, String footer, boolean useNotice,
            String exportRoomId, String firstEventId) throws Exception {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);

        StringBuilder reasoning = new StringBuilder();
        StringBuilder responseContent = new StringBuilder();
        String actualModel = null;
        long lastUpdate = System.currentTimeMillis();
        final long startTime = System.currentTimeMillis();

        int updateCount = 0;
        String[] clockFaces = {"🕛", "🕧", "🕐", "🕜", "🕑", "🕝", "🕒", "🕞", "🕓", "🕟", "🕔", "🕠", "🕕", "🕡", "🕖", "🕢", "🕗", "🕣", "🕘", "🕤", "🕙", "🕥", "🕚", "🕦"};

        int lineCount = 0;
        boolean gotDone = false;
        boolean sentAsNotice = false;

        try {
            System.out.println("Starting " + aiName + " streaming request...");
            HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                String errorBody = response.body().collect(java.util.stream.Collectors.joining("\n"));
                throw new Exception("HTTP " + response.statusCode() + ": " + errorBody);
            }

            try (java.util.stream.Stream<String> lines = response.body()) {
                java.util.Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    if (abortFlag != null && abortFlag.get()) {
                        System.out.println(aiName + " streaming aborted by flag.");
                        break;
                    }
                    String line = it.next();
                    lineCount = lineCount + 1;
                    String data = line.trim();
                    if (data.isEmpty()) continue;

                    String json = null;
                    if (data.startsWith("data:") && !data.contains("[DONE]")) {
                        json = data.substring(5).trim();
                    } else if (data.startsWith("{")) {
                        json = data;
                    }

                    if (json != null && !json.isEmpty()) {
                        JsonNode node = mapper.readTree(json);
                        if (node.has("error")) {
                            JsonNode errorNode = node.get("error");
                            String code = "200";
                            if (errorNode.has("status_code")) code = errorNode.get("status_code").asText();
                            else if (errorNode.has("statusCode")) code = errorNode.get("statusCode").asText();
                            throw new Exception("Status: " + code + " Body: " + errorNode.toString());
                        }
                        
                        if (node.has("model") && (actualModel == null || actualModel.isEmpty())) {
                            actualModel = node.get("model").asText();
                        }

                        // Handle Ollama native format
                        if (node.has("message")) {
                            JsonNode message = node.get("message");
                            if (message.has("content")) {
                                responseContent.append(message.get("content").asText());
                            }
                        } 
                        // Handle OpenAI/ArliAI format
                        else if (node.has("choices")) {
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
                            }
                        }

                        // Check if Ollama is done
                        if (node.has("done") && node.get("done").asBoolean()) {
                            gotDone = true;
                        }

                        long now = System.currentTimeMillis();
                        if ((responseContent.length() > 0 || reasoning.length() > 0) && now - lastUpdate > 10000) {
                            lastUpdate = now;
                            String rendered = buildStreamingOutput(reasoning, responseContent, footer, clockFaces, updateCount++, startTime);
                            boolean isThinkingOnly = reasoning.length() > 0 && responseContent.length() == 0;
                            if (eventIdHolder[0] == null) {
                                sentAsNotice = !isThinkingOnly && useNotice;
                                eventIdHolder[0] = isThinkingOnly
                                        ? matrixClient.sendMarkdownWithEventId(responseRoomId, rendered)
                                        : (useNotice
                                        ? matrixClient.sendMarkdownNoticeWithEventId(responseRoomId, rendered)
                                        : matrixClient.sendMarkdownWithEventId(responseRoomId, rendered));
                            } else {
                                if (sentAsNotice) {
                                    matrixClient.updateMarkdownNoticeMessage(responseRoomId, eventIdHolder[0], rendered);
                                } else {
                                    matrixClient.updateMarkdownMessage(responseRoomId, eventIdHolder[0], rendered);
                                }
                            }
                        }
                    } else if (data.contains("[DONE]")) {
                        System.out.println(aiName + " streaming finished normally ([DONE] received).");
                        gotDone = true;
                        break;
                    }
                    
                    if (gotDone) break;
                }
            }
        } catch (Exception e) {
            System.err.println("Error during " + aiName + " streaming call: " + e.getMessage());
            e.printStackTrace();
            throw new Exception(e.getMessage(), e);
        }

        if (responseContent.length() == 0 && reasoning.length() == 0) {
            String details = (lineCount == 0) ? "No SSE data received" : 
                           (gotDone ? "Stream ended with [DONE] but no content" : "Stream incomplete, no [DONE] received");
            throw new Exception(aiName + " error: " + details + ".");
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

        finalOutput = appendMessageLink(finalOutput, exportRoomId, firstEventId, aiName, actualModel);

        if (eventIdHolder[0] == null) {
            boolean isThinkingOnly = responseContent.toString().trim().isEmpty() && reasoning.length() > 0;
            sentAsNotice = !isThinkingOnly && useNotice;
            eventIdHolder[0] = isThinkingOnly
                    ? matrixClient.sendMarkdownWithEventId(responseRoomId, finalOutput)
                    : (useNotice
                    ? matrixClient.sendMarkdownNoticeWithEventId(responseRoomId, finalOutput)
                    : matrixClient.sendMarkdownWithEventId(responseRoomId, finalOutput));
        } else {
            if (sentAsNotice) {
                matrixClient.updateMarkdownNoticeMessage(responseRoomId, eventIdHolder[0], finalOutput);
            } else {
                matrixClient.updateMarkdownMessage(responseRoomId, eventIdHolder[0], finalOutput);
            }
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

            performAIQuery(responseRoomId, exportRoomId, result, question, promptPrefix, abortFlag, Backend.AUTO, null, AI_TIMEOUT_SECONDS, statusEventId, null);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdownNotice(responseRoomId, "Error summarizing unread messages: " + e.getMessage());
        }
    }

    public void queryAsk(String responseRoomId, String exportRoomId, String fromToken, String question, String promptPrefix,
                         java.util.concurrent.atomic.AtomicBoolean abortFlag, String forcedModel, int timeoutSeconds, 
                         Backend preferredBackend, ZoneId zoneId) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            // Target context window for Arli AI is 16k tokens.
            // We reserve ~4000 tokens for the generated response to be safe.
            int targetPromptTokens = 12000;

            // Calculate base tokens consumed by prompts and the user's question
            String emptyPrompt = buildPrompt(question, new ArrayList<>(), promptPrefix);
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

            performAIQuery(responseRoomId, exportRoomId, history, question, promptPrefix, abortFlag, preferredBackend, forcedModel, timeoutSeconds, statusEventId, null);

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendMarkdownNotice(responseRoomId, "Error querying AI: " + e.getMessage());
        }
    }

    private String callCerebras(String prompt, String model, boolean skipSystem, int timeoutSeconds) throws Exception {
        return callNonStreaming(getProviderConfig(Backend.CEREBRAS), prompt, model, skipSystem, false, timeoutSeconds);
    }

    private String callNonStreaming(ProviderConfig provider, String prompt, String model, boolean skipSystem,
            boolean isAsk, int timeoutSeconds) throws Exception {
        return AIRequestQueue.run(provider.displayName + " (" + model + ")",
                () -> callNonStreamingUnqueued(provider, prompt, model, skipSystem, isAsk, timeoutSeconds));
    }

    private String callNonStreamingUnqueued(ProviderConfig provider, String prompt, String model, boolean skipSystem,
            boolean isAsk, int timeoutSeconds) throws Exception {
        HttpRequest request = buildChatCompletionRequest(provider, prompt, model, skipSystem, isAsk, false, timeoutSeconds);

        System.out.println("Starting " + provider.displayName + " (" + model + ") request...");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            try {
                JsonNode root = mapper.readTree(response.body());
                JsonNode choice = root.path("choices").get(0);
                if (choice == null) {
                    throw new Exception("Missing 'choices' array");
                }
                String text = choice.path("message").path("content").asText(null);
                if (text == null || text.trim().isEmpty()) {
                    throw new Exception("No response from " + provider.displayName + " (" + model + ").");
                }
                return text;
            } catch (Exception e) {
                throw new Exception("Unexpected 200 response from " + provider.displayName + " (" + model
                        + "). Body: " + response.body(), e);
            }
        } else {
            throw new Exception(provider.displayName + " (" + model + ") failed. Status: "
                    + response.statusCode() + ", Body: " + response.body());
        }
    }

    private HttpRequest buildChatCompletionRequest(ProviderConfig provider, String prompt, String model,
            boolean skipSystem, boolean isAsk, boolean stream, int timeoutSeconds) throws Exception {
        if (provider == null || provider.apiKey == null || provider.apiKey.isEmpty()) {
            throw new Exception(provider == null ? "AI provider is not configured."
                    : provider.apiKeyName + " is not configured.");
        }

        Map<String, Object> payload = new HashMap<>();
        // For OpenRouter: send native fallback models array and request ZDR enforcement
        if (provider.backend == Backend.OPENROUTER) {
            java.util.List<String> modelsToSend = new ArrayList<>();
            try {
                java.util.Set<String> zdr = getOpenrouterZdrSet();
                for (String m : openrouterModels) {
                    if (m.contains(":free")) {
                        if (zdr != null && zdr.contains(m)) modelsToSend.add(m);
                    } else {
                        modelsToSend.add(m);
                    }
                }
            } catch (Exception e) {
                // On failure to fetch ZDR list, fall back to configured models
                modelsToSend = new ArrayList<>(openrouterModels);
            }
            if (modelsToSend.isEmpty()) modelsToSend = new ArrayList<>(openrouterModels);
            payload.put("models", modelsToSend);
            payload.put("messages", buildMessages(prompt, skipSystem, isAsk));
            payload.put("stream", stream);
            payload.putAll(provider.extraPayload);
            payload.put("provider", Map.of("zdr", true));
        } else {
            payload.put("model", model);
            payload.put("messages", buildMessages(prompt, skipSystem, isAsk));
            payload.put("stream", stream);
            payload.putAll(provider.extraPayload);
        }
        if (provider.backend == Backend.ARLIAI) {
            applyArliAiNonThinkingDefaults(payload);
        }
        String jsonPayload = mapper.writeValueAsString(payload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(provider.url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + provider.apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

        if (stream) {
            builder.header("Accept", "text/event-stream");
        }
        for (Map.Entry<String, String> header : provider.extraHeaders.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        return builder.build();
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

    private List<Map<String, String>> buildMessages(String prompt, boolean skipSystem, boolean isAsk) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (!skipSystem) {
            String systemPrompt = isAsk ? Prompts.SYSTEM_ASK : Prompts.SYSTEM_OVERVIEW;
            messages.add(Map.of("role", "system", "content", systemPrompt));
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

    protected String appendMessageLink(String aiAnswer, String exportRoomId, String firstEventId, String provider, String model) {
        String footer = "";
        if (provider != null && !provider.isEmpty() && model != null && !model.isEmpty()) {
            footer += "\n\n_" + provider + " / " + model + "_";
        }
        if (firstEventId != null) {
            String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + firstEventId;
            footer += "\n\n" + messageLink;
        }
        return aiAnswer + footer;
    }

    private static List<String> filterUserMessages(List<String> logs, String userId) {
        if (logs == null || logs.isEmpty()) {
            return logs;
        }
        List<String> filtered = new ArrayList<>();
        for (String log : logs) {
            if (!log.contains("<" + userId + ">")) {
                filtered.add(log);
            }
        }
        return filtered;
    }

}
