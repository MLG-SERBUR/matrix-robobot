package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agentic AI-powered search service for finding specific files, conversations,
 * images, or videos in chat logs.
 * 
 * Uses a combination of semantic search, text search, and AI analysis to
 * iteratively narrow down results. Designed for ArliAI's 12k context limit
 * with single-request processing (no parallel).
 * 
 * Flow:
 * 1. Pre-filter logs using semantic + text search
 * 2. Send candidates to AI with system prompt
 * 3. AI analyzes and either returns results or requests more context
 * 4. If more context needed, fetch surrounding messages and repeat
 */
public class AiSearchService {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;
    private final String arliApiKey;
    private final RoomHistoryManager historyManager;
    private final MatrixClient matrixClient;

    // ArliAI context limit is 12k tokens
    // Reserve ~3k for system prompt, user prompt, and response
    // Leave ~9k for chat log content
    private static final int MAX_CONTEXT_TOKENS = 12000;
    private static final int RESERVED_TOKENS = 3000;
    private static final int AVAILABLE_LOG_TOKENS = MAX_CONTEXT_TOKENS - RESERVED_TOKENS;
    
    // Maximum candidates to send in initial pass
    private static final int MAX_INITIAL_CANDIDATES = 30;
    // Maximum candidates to send in refinement passes
    private static final int MAX_REFINEMENT_CANDIDATES = 20;
    // Maximum iterations before giving up
    private static final int MAX_ITERATIONS = 5;

    private static final String SYSTEM_PROMPT = 
        "You are a search assistant for a Matrix chat room. Your task is to help the user find specific messages, files, images, videos, or conversations.\n\n" +
        "You will receive:\n" +
        "1. The user's search query\n" +
        "2. A list of candidate messages (pre-filtered by relevance)\n" +
        "3. Each message has an event ID, timestamp, sender, and content\n\n" +
        "Your job:\n" +
        "- Analyze the candidates and identify which ones match the user's query\n" +
        "- If you find matches, return them with their event IDs\n" +
        "- If you need MORE CONTEXT around a specific message (e.g., to see what was discussed before/after), say \"CONTEXT:event_id\" where event_id is the message you want context around\n" +
        "- If no matches are found, say \"NO_MATCH\"\n" +
        "- Be specific about what you're looking for (filenames, image descriptions, video mentions, etc.)\n\n" +
        "IMPORTANT:\n" +
        "- Media messages often have filenames in brackets like [IMAGE: cat.jpg] or [VIDEO: vacation.mp4]\n" +
        "- Look for file extensions (.jpg, .png, .mp4, .pdf, etc.)\n" +
        "- Consider context clues like \"posted\", \"shared\", \"sent\", \"uploaded\"\n" +
        "- If the user mentions a specific topic, look for related keywords\n" +
        "- Return event IDs so the user can jump to the message\n\n" +
        "Format your response as:\n" +
        "MATCHES:\n" +
        "- event_id: [event_id] - [brief description of why this matches]\n" +
        "OR\n" +
        "CONTEXT:event_id\n" +
        "OR\n" +
        "NO_MATCH";

    public AiSearchService(HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, 
                           String accessToken, String arliApiKey) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
        this.arliApiKey = arliApiKey;
        this.historyManager = new RoomHistoryManager(httpClient, mapper, homeserverUrl, accessToken);
        this.matrixClient = new MatrixClient(httpClient, mapper, homeserverUrl, accessToken);
    }

    /**
     * Perform an AI-powered search for messages in chat logs.
     * 
     * @param responseRoomId Room to send responses to
     * @param exportRoomId Room to search in
     * @param hours Number of hours to search back
     * @param fromToken Pagination token
     * @param query User's search query
     * @param zoneId User's timezone
     * @param abortFlag Abort flag for cancellation
     */
    public void performAiSearch(String responseRoomId, String exportRoomId, int hours, 
                                String fromToken, String query, ZoneId zoneId, 
                                AtomicBoolean abortFlag) {
        try {
            String timeInfo = "last " + hours + "h";
            
            // Send initial status
            String statusEventId = matrixClient.sendTextWithEventId(responseRoomId, 
                "\uD83D\uDD0D AI Search: Searching for \"" + query + "\" in " + timeInfo + "...");

            // Step 1: Fetch all messages in the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            AtomicLong lastProgressUpdate = new AtomicLong(System.currentTimeMillis());
            RoomHistoryManager.ProgressCallback progressCallback = (msgCount, estTokens) -> {
                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate.get() >= 3000) {
                    lastProgressUpdate.set(now);
                    matrixClient.updateTextMessage(responseRoomId, statusEventId,
                        "\uD83D\uDD0D AI Search: Gathering messages... (" + msgCount + " found)");
                }
            };

            RoomHistoryManager.ChatLogsWithIds allMessages = historyManager.fetchRoomHistoryWithIds(
                exportRoomId, hours, fromToken, startTime, endTime, zoneId, true, abortFlag, progressCallback);

            if (abortFlag.get()) {
                matrixClient.updateTextMessage(responseRoomId, statusEventId, "AI Search aborted.");
                return;
            }

            if (allMessages.logs.isEmpty()) {
                matrixClient.updateTextMessage(responseRoomId, statusEventId, 
                    "No messages found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            matrixClient.updateTextMessage(responseRoomId, statusEventId,
                "\uD83D\uDD0D AI Search: Found " + allMessages.logs.size() + " messages. Pre-filtering...");

            // Step 2: Pre-filter using semantic + text search
            List<SearchCandidate> candidates = preFilterMessages(query, allMessages, abortFlag);

            if (abortFlag.get()) {
                matrixClient.updateTextMessage(responseRoomId, statusEventId, "AI Search aborted.");
                return;
            }

            if (candidates.isEmpty()) {
                matrixClient.updateTextMessage(responseRoomId, statusEventId,
                    "No relevant messages found for \"" + query + "\" after pre-filtering.");
                return;
            }

            matrixClient.updateTextMessage(responseRoomId, statusEventId,
                "\uD83D\uDD0D AI Search: Found " + candidates.size() + " candidates. Analyzing with AI...");

            // Step 3: Agentic loop - iteratively analyze with AI
            List<SearchCandidate> currentCandidates = new ArrayList<>(candidates);
            List<SearchResult> finalResults = new ArrayList<>();
            Set<String> examinedEventIds = new HashSet<>();
            int iteration = 0;

            while (iteration < MAX_ITERATIONS && !currentCandidates.isEmpty() && !abortFlag.get()) {
                iteration++;
                
                // Limit candidates for this iteration
                int maxCandidates = iteration == 1 ? MAX_INITIAL_CANDIDATES : MAX_REFINEMENT_CANDIDATES;
                List<SearchCandidate> batchCandidates = currentCandidates.subList(0, 
                    Math.min(maxCandidates, currentCandidates.size()));

                matrixClient.updateTextMessage(responseRoomId, statusEventId,
                    "\uD83D\uDD0D AI Search: Analyzing batch " + iteration + "/" + MAX_ITERATIONS + 
                    " (" + batchCandidates.size() + " candidates)...");

                // Build prompt for AI
                String aiPrompt = buildAiPrompt(query, batchCandidates, exportRoomId);

                // Query ArliAI (single request, no parallel)
                String aiResponse = queryArliAI(aiPrompt, abortFlag);

                if (abortFlag.get()) {
                    matrixClient.updateTextMessage(responseRoomId, statusEventId, "AI Search aborted.");
                    return;
                }

                if (aiResponse == null) {
                    // Rate limit or error - abort with message
                    matrixClient.updateTextMessage(responseRoomId, statusEventId,
                        "AI Search failed: Rate limit or API error. Please try again later.");
                    return;
                }

                // Parse AI response
                AiParseResult parseResult = parseAiResponse(aiResponse);

                if (parseResult.hasMatches) {
                    // Found matches - add to results
                    for (SearchResult result : parseResult.results) {
                        if (!examinedEventIds.contains(result.eventId)) {
                            finalResults.add(result);
                            examinedEventIds.add(result.eventId);
                        }
                    }
                    
                    // If we have enough results, stop
                    if (finalResults.size() >= 5) {
                        break;
                    }
                }

                if (parseResult.requestedContextEventId != null) {
                    // AI wants more context around a specific message
                    String contextEventId = parseResult.requestedContextEventId;
                    
                    if (!examinedEventIds.contains(contextEventId)) {
                        matrixClient.updateTextMessage(responseRoomId, statusEventId,
                            "\uD83D\uDD0D AI Search: Fetching context around message...");
                        
                        // Fetch context around the requested message
                        List<SearchCandidate> contextCandidates = fetchContextAroundMessage(
                            exportRoomId, contextEventId, zoneId, abortFlag);
                        
                        if (abortFlag.get()) {
                            matrixClient.updateTextMessage(responseRoomId, statusEventId, "AI Search aborted.");
                            return;
                        }

                        // Add context candidates to next iteration
                        for (SearchCandidate cc : contextCandidates) {
                            if (!examinedEventIds.contains(cc.eventId)) {
                                currentCandidates.add(cc);
                            }
                        }
                        
                        examinedEventIds.add(contextEventId);
                    }
                }

                if (!parseResult.hasMatches && parseResult.requestedContextEventId == null) {
                    // AI says NO_MATCH - try next batch
                    currentCandidates.removeAll(batchCandidates);
                }
            }

            // Step 4: Format and send results
            if (finalResults.isEmpty()) {
                matrixClient.updateTextMessage(responseRoomId, statusEventId,
                    "No matches found for \"" + query + "\" after AI analysis.");
            } else {
                StringBuilder response = new StringBuilder();
                response.append("### \uD83D\uDD0D AI Search Results\n\n");
                response.append("**Query:** \"").append(query).append("\"\n");
                response.append("**Time range:** ").append(timeInfo).append("\n");
                response.append("**Results:** ").append(finalResults.size()).append(" matches\n\n");

                for (SearchResult result : finalResults) {
                    String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + result.eventId;
                    response.append("**[").append(result.timestamp).append("]** <").append(result.sender).append(">\n");
                    response.append(result.message).append("\n");
                    response.append("_").append(result.reason).append("_\n");
                    response.append(messageLink).append("\n\n");
                }

                matrixClient.updateMarkdownMessage(responseRoomId, statusEventId, response.toString());
            }

        } catch (Exception e) {
            System.err.println("AI Search failed: " + e.getMessage());
            e.printStackTrace();
            matrixClient.sendText(responseRoomId, "AI Search failed: " + e.getMessage());
        }
    }

    /**
     * Pre-filter messages using semantic similarity and text matching.
     * Returns candidates sorted by relevance.
     */
    private List<SearchCandidate> preFilterMessages(String query, RoomHistoryManager.ChatLogsWithIds allMessages,
                                                     AtomicBoolean abortFlag) {
        List<SearchCandidate> candidates = new ArrayList<>();
        
        // Tokenize query for semantic matching
        String[] queryTerms = tokenize(query.toLowerCase());
        String queryLower = query.toLowerCase();
        
        // Also look for file extensions and media keywords
        boolean hasMediaKeywords = queryLower.contains("image") || queryLower.contains("video") || 
                                   queryLower.contains("file") || queryLower.contains("photo") ||
                                   queryLower.contains("picture") || queryLower.contains("pdf") ||
                                   queryLower.contains("jpg") || queryLower.contains("png") ||
                                   queryLower.contains("mp4") || queryLower.contains("gif");

        // Calculate document frequency for TF-IDF
        Map<String, Integer> dfMap = new java.util.HashMap<>();
        for (String log : allMessages.logs) {
            Set<String> uniqueWords = new HashSet<>(java.util.Arrays.asList(tokenize(log.toLowerCase())));
            for (String word : uniqueWords) {
                dfMap.put(word, dfMap.getOrDefault(word, 0) + 1);
            }
        }

        double n = allMessages.logs.size();

        for (int i = 0; i < allMessages.logs.size(); i++) {
            if (abortFlag != null && abortFlag.get()) {
                return new ArrayList<>();
            }

            String log = allMessages.logs.get(i);
            String eventId = allMessages.eventIds.get(i);
            String logLower = log.toLowerCase();

            // Parse the log line
            Matcher matcher = Pattern.compile("\\[(.*?)\\] <(.*?)> (.*)").matcher(log);
            if (!matcher.matches()) continue;

            String timestamp = matcher.group(1);
            String sender = matcher.group(2);
            String message = matcher.group(3);

            // Calculate relevance score
            double score = 0;

            // 1. Exact phrase match (highest weight)
            if (logLower.contains(queryLower)) {
                score += 10.0;
            }

            // 2. TF-IDF score for individual terms
            String[] docTerms = tokenize(logLower);
            int matches = 0;
            for (String qt : queryTerms) {
                double tf = 0;
                for (String dt : docTerms) {
                    if (dt.equals(qt)) tf++;
                }
                if (tf > 0) {
                    matches++;
                    double idf = Math.log(n / (double) dfMap.getOrDefault(qt, 1) + 1.0);
                    score += tf * idf;
                }
            }

            // 3. Media keyword boost
            if (hasMediaKeywords) {
                if (logLower.contains("[image:") || logLower.contains("[video:") || 
                    logLower.contains("[file:") || logLower.contains("[audio:")) {
                    score += 5.0;
                }
                // Check for file extensions
                for (String ext : new String[]{".jpg", ".jpeg", ".png", ".gif", ".mp4", ".pdf", ".zip", ".doc", ".docx"}) {
                    if (logLower.contains(ext)) {
                        score += 3.0;
                    }
                }
            }

            // 4. Proximity boost for multiple query terms
            if (queryTerms.length > 1 && matches > 1) {
                score += calculateProximityScore(queryTerms, docTerms) * 2.0;
            }

            // Only include if there's some relevance
            if (score > 0) {
                candidates.add(new SearchCandidate(eventId, timestamp, sender, message, log, score));
            }
        }

        // Sort by score descending
        candidates.sort((a, b) -> Double.compare(b.score, a.score));

        // Return top candidates (limit to avoid overwhelming AI)
        int maxResults = Math.min(100, candidates.size());
        return new ArrayList<>(candidates.subList(0, maxResults));
    }

    /**
     * Build the prompt for ArliAI with candidates.
     */
    private String buildAiPrompt(String query, List<SearchCandidate> candidates, String exportRoomId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Search query: \"").append(query).append("\"\n\n");
        sb.append("Candidate messages:\n\n");

        int currentTokens = RoomHistoryManager.estimateTokens(SYSTEM_PROMPT) + 
                           RoomHistoryManager.estimateTokens(query) + 200; // Buffer

        for (SearchCandidate candidate : candidates) {
            String candidateLine = formatCandidate(candidate, exportRoomId);
            int lineTokens = RoomHistoryManager.estimateTokens(candidateLine);
            
            if (currentTokens + lineTokens > AVAILABLE_LOG_TOKENS) {
                sb.append("... [truncated - ").append(candidates.size()).append(" total candidates]");
                break;
            }

            sb.append(candidateLine).append("\n");
            currentTokens += lineTokens;
        }

        return sb.toString();
    }

    /**
     * Format a candidate for the AI prompt.
     */
    private String formatCandidate(SearchCandidate candidate, String exportRoomId) {
        return String.format("[ID:%s] [%s] <%s> %s (relevance: %.1f)",
            candidate.eventId, candidate.timestamp, candidate.sender, 
            candidate.message, candidate.score);
    }

    /**
     * Query ArliAI with the given prompt.
     * Returns null on rate limit or error.
     */
    private String queryArliAI(String userPrompt, AtomicBoolean abortFlag) {
        if (arliApiKey == null || arliApiKey.isEmpty()) {
            System.err.println("ARLI_API_KEY is not configured.");
            return null;
        }

        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", userPrompt));

            Map<String, Object> payload = Map.of(
                "model", "Qwen3.5-27B-Derestricted",
                "messages", messages,
                "stream", false,
                "dry_multiplier", 0.4
            );

            String jsonPayload = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.arliai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + arliApiKey)
                .timeout(Duration.ofSeconds(AIService.AI_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                System.err.println("ArliAI rate limit hit.");
                return null;
            }

            if (response.statusCode() == 403) {
                String body = response.body();
                if (body.contains("exceeded the maximum context length")) {
                    System.err.println("ArliAI context length exceeded.");
                    return null;
                }
                System.err.println("ArliAI 403 error: " + body);
                return null;
            }

            if (response.statusCode() != 200) {
                System.err.println("ArliAI error: " + response.statusCode() + " - " + response.body());
                return null;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode choice = root.path("choices").get(0);
            if (choice == null) {
                System.err.println("ArliAI: No choices in response.");
                return null;
            }

            return choice.path("message").path("content").asText(null);

        } catch (java.net.http.HttpTimeoutException e) {
            System.err.println("ArliAI timeout: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("ArliAI error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parse the AI response to extract matches or context requests.
     */
    private AiParseResult parseAiResponse(String response) {
        AiParseResult result = new AiParseResult();
        
        if (response == null || response.trim().isEmpty()) {
            return result;
        }

        String responseLower = response.toLowerCase();

        // Check for NO_MATCH
        if (responseLower.contains("no_match") && !responseLower.contains("match")) {
            return result;
        }

        // Check for CONTEXT request
        Pattern contextPattern = Pattern.compile("context:([\\$][A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
        Matcher contextMatcher = contextPattern.matcher(response);
        if (contextMatcher.find()) {
            result.requestedContextEventId = contextMatcher.group(1);
            if (!result.requestedContextEventId.startsWith("$")) {
                result.requestedContextEventId = "$" + result.requestedContextEventId;
            }
        }

        // Parse MATCHES
        Pattern matchPattern = Pattern.compile(
            "-\\s*event_id:\\s*([\\$][A-Za-z0-9_-]+)\\s*-\\s*(.*?)(?:\\n|$)", 
            Pattern.CASE_INSENSITIVE);
        Matcher matchMatcher = matchPattern.matcher(response);
        
        while (matchMatcher.find()) {
            String eventId = matchMatcher.group(1);
            String reason = matchMatcher.group(2).trim();
            
            if (!eventId.startsWith("$")) {
                eventId = "$" + eventId;
            }
            
            result.results.add(new SearchResult(eventId, reason));
            result.hasMatches = true;
        }

        return result;
    }

    /**
     * Fetch messages around a specific event for additional context.
     */
    private List<SearchCandidate> fetchContextAroundMessage(String roomId, String eventId, 
                                                             ZoneId zoneId, AtomicBoolean abortFlag) {
        List<SearchCandidate> contextCandidates = new ArrayList<>();
        
        try {
            // Get context around the event (5 messages before and after)
            String url = homeserverUrl + "/_matrix/client/v3/rooms/"
                + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                + "/context/" + URLEncoder.encode(eventId, StandardCharsets.UTF_8) + "?limit=10";
            
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (resp.statusCode() != 200) {
                System.err.println("Failed to get context: " + resp.statusCode());
                return contextCandidates;
            }

            JsonNode root = mapper.readTree(resp.body());
            
            // Process the event itself
            JsonNode event = root.path("event");
            if (!event.isMissingNode()) {
                SearchCandidate mainCandidate = parseEventToCandidate(event, zoneId);
                if (mainCandidate != null) {
                    contextCandidates.add(mainCandidate);
                }
            }

            // Process events before
            JsonNode eventsBefore = root.path("events_before");
            if (eventsBefore.isArray()) {
                for (JsonNode ev : eventsBefore) {
                    if (abortFlag != null && abortFlag.get()) return contextCandidates;
                    SearchCandidate candidate = parseEventToCandidate(ev, zoneId);
                    if (candidate != null) {
                        contextCandidates.add(candidate);
                    }
                }
            }

            // Process events after
            JsonNode eventsAfter = root.path("events_after");
            if (eventsAfter.isArray()) {
                for (JsonNode ev : eventsAfter) {
                    if (abortFlag != null && abortFlag.get()) return contextCandidates;
                    SearchCandidate candidate = parseEventToCandidate(ev, zoneId);
                    if (candidate != null) {
                        contextCandidates.add(candidate);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Error fetching context: " + e.getMessage());
        }

        return contextCandidates;
    }

    /**
     * Parse a JSON event into a SearchCandidate.
     */
    private SearchCandidate parseEventToCandidate(JsonNode ev, ZoneId zoneId) {
        if (!"m.room.message".equals(ev.path("type").asText(null))) {
            return null;
        }

        String eventId = ev.path("event_id").asText(null);
        String sender = ev.path("sender").asText(null);
        String body = ev.path("content").path("body").asText(null);
        long originServerTs = ev.path("origin_server_ts").asLong(0);

        if (eventId == null || sender == null || body == null) {
            return null;
        }

        String timestamp = Instant.ofEpochMilli(originServerTs)
            .atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));

        String formattedLog = "[" + timestamp + "] <" + sender + "> " + body;
        
        return new SearchCandidate(eventId, timestamp, sender, body, formattedLog, 1.0);
    }

    /**
     * Tokenize text for similarity matching.
     */
    private static String[] tokenize(String text) {
        if (text == null) return new String[0];
        String normalized = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
        String[] words = normalized.split("\\s+");
        List<String> filtered = new ArrayList<>();
        Set<String> stopWords = new HashSet<>(java.util.Arrays.asList(
            "the", "is", "at", "which", "on", "and", "a", "an", "of", "to", "in", "it", "for", "with", "as"));

        for (String w : words) {
            if (w.length() > 2 && !stopWords.contains(w)) {
                filtered.add(w);
            }
        }
        return filtered.toArray(new String[0]);
    }

    /**
     * Calculate proximity score for query terms in document.
     */
    private double calculateProximityScore(String[] queryTerms, String[] docTerms) {
        if (queryTerms.length < 2) return 0.0;

        Map<String, List<Integer>> positions = new java.util.HashMap<>();
        for (int i = 0; i < docTerms.length; i++) {
            for (String qt : queryTerms) {
                if (docTerms[i].equals(qt)) {
                    positions.computeIfAbsent(qt, k -> new ArrayList<>()).add(i);
                }
            }
        }

        if (positions.size() < 2) return 0.0;

        double maxProximity = 0;
        for (int i = 0; i < queryTerms.length - 1; i++) {
            List<Integer> pos1 = positions.get(queryTerms[i]);
            List<Integer> pos2 = positions.get(queryTerms[i + 1]);

            if (pos1 == null || pos2 == null) continue;

            for (int p1 : pos1) {
                for (int p2 : pos2) {
                    int dist = Math.abs(p1 - p2);
                    if (dist > 0) {
                        maxProximity += 1.0 / (double) dist;
                    }
                }
            }
        }

        return Math.min(1.0, maxProximity);
    }

    // Inner classes for data structures

    private static class SearchCandidate {
        final String eventId;
        final String timestamp;
        final String sender;
        final String message;
        final String fullLog;
        final double score;

        SearchCandidate(String eventId, String timestamp, String sender, 
                       String message, String fullLog, double score) {
            this.eventId = eventId;
            this.timestamp = timestamp;
            this.sender = sender;
            this.message = message;
            this.fullLog = fullLog;
            this.score = score;
        }
    }

    private static class SearchResult {
        final String eventId;
        final String reason;
        String timestamp;
        String sender;
        String message;

        SearchResult(String eventId, String reason) {
            this.eventId = eventId;
            this.reason = reason;
        }
    }

    private static class AiParseResult {
        boolean hasMatches = false;
        String requestedContextEventId = null;
        List<SearchResult> results = new ArrayList<>();
    }
}
