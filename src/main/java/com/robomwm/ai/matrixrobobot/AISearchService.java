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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles AI-powered search for finding specific files, images, videos, or conversations.
 * Uses an agentic approach to iteratively search through chat history while managing AI context limits.
 */
public class AISearchService {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;
    private final String arliApiKey;
    private final String cerebrasApiKey;
    private final RoomHistoryManager historyManager;
    private final MatrixClient matrixClient;

    // Context limits for AI models
    private static final int MAX_TOKENS_PER_BATCH = 8000; // Conservative limit per batch
    private static final int MAX_RESULTS = 50; // Maximum results to return (increased for finding multiple discussions)
    private static final int MAX_SEARCH_ITERATIONS = 50; // Maximum number of batches to search

    public AISearchService(HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, String accessToken,
                           String arliApiKey, String cerebrasApiKey) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
        this.arliApiKey = arliApiKey;
        this.cerebrasApiKey = cerebrasApiKey;
        this.historyManager = new RoomHistoryManager(httpClient, mapper, homeserverUrl, accessToken);
        this.matrixClient = new MatrixClient(httpClient, mapper, homeserverUrl, accessToken);
    }

    /**
     * Result of an AI search operation
     */
    public static class SearchResult {
        public final List<SearchMatch> matches;
        public final int totalMessagesSearched;
        public final String error;
        public final boolean truncated;

        public SearchResult(List<SearchMatch> matches, int totalMessagesSearched, boolean truncated) {
            this(matches, totalMessagesSearched, truncated, null);
        }

        public SearchResult(List<SearchMatch> matches, int totalMessagesSearched, boolean truncated, String error) {
            this.matches = matches;
            this.totalMessagesSearched = totalMessagesSearched;
            this.truncated = truncated;
            this.error = error;
        }
    }

    /**
     * A single search match
     */
    public static class SearchMatch {
        public final String eventId;
        public final String sender;
        public final long timestamp;
        public final String body;
        public final String mediaType; // "IMAGE", "VIDEO", "FILE", "AUDIO", or null for text
        public final String filename;
        public final String matchReason; // Why the AI认为这是匹配的

        public SearchMatch(String eventId, String sender, long timestamp, String body, 
                          String mediaType, String filename, String matchReason) {
            this.eventId = eventId;
            this.sender = sender;
            this.timestamp = timestamp;
            this.body = body;
            this.mediaType = mediaType;
            this.filename = filename;
            this.matchReason = matchReason;
        }
    }

    /**
     * Perform an AI-powered search for files or conversations.
     * 
     * @param responseRoomId Room to send responses to
     * @param exportRoomId Room to search in
     * @param hours Number of hours to search back (0 for all history)
     * @param fromToken Pagination token
     * @param query Natural language search query
     * @param zoneId User's timezone
     * @param abortFlag Abort flag for cancellation
     */
    public void performAISearch(String responseRoomId, String exportRoomId, int hours, String fromToken,
                                String query, ZoneId zoneId, AtomicBoolean abortFlag) {
        try {
            String timeInfo = hours > 0 ? "last " + hours + "h" : "all history";
            
            // Send initial status message
            String initialMessage = "\uD83D\uDD0D AI Search: Looking for \"" + query + "\" in " + timeInfo + "...";
            String statusEventId = matrixClient.sendTextWithEventId(responseRoomId, initialMessage);
            
            // Create progress callback
            final AtomicLong lastProgressUpdate = new AtomicLong(System.currentTimeMillis());
            
            // Determine search scope
            long startTime = hours > 0 ? System.currentTimeMillis() - (long) hours * 3600L * 1000L : -1;
            long endTime = System.currentTimeMillis();
            
            // Get pagination token
            String token = getPaginationToken(exportRoomId, fromToken);
            if (token == null) {
                matrixClient.updateTextMessage(responseRoomId, statusEventId, 
                        "❌ Failed to get pagination token for " + exportRoomId);
                return;
            }
            
            // Agentic search: iterate through batches
            List<SearchMatch> allMatches = new ArrayList<>();
            int totalMessagesSearched = 0;
            int iteration = 0;
            boolean searchComplete = false;
            
            while (token != null && iteration < MAX_SEARCH_ITERATIONS && allMatches.size() < MAX_RESULTS) {
                if (abortFlag != null && abortFlag.get()) {
                    matrixClient.updateTextMessage(responseRoomId, statusEventId, 
                            "\uD83D\uDEAB AI Search aborted after searching " + totalMessagesSearched + " messages.");
                    return;
                }
                
                iteration++;
                
                // Update progress
                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate.get() >= 3000) {
                    lastProgressUpdate.set(now);
                    matrixClient.updateTextMessage(responseRoomId, statusEventId,
                            "\uD83D\uDD0D AI Search: Iteration " + iteration + " | " + 
                            totalMessagesSearched + " messages searched | " + 
                            allMatches.size() + " matches found...");
                }
                
                // Fetch a batch of messages
                BatchResult batch = fetchMessageBatch(exportRoomId, token, startTime, endTime, zoneId, abortFlag);
                if (batch == null || batch.messages.isEmpty()) {
                    break;
                }
                
                totalMessagesSearched += batch.messages.size();
                token = batch.nextToken;
                
                // Use AI to analyze this batch
                List<SearchMatch> batchMatches = analyzeBatchWithAI(batch.messages, query, exportRoomId, zoneId, abortFlag);
                
                if (batchMatches != null && !batchMatches.isEmpty()) {
                    allMatches.addAll(batchMatches);
                    
                    // Truncate if we have too many results
                    if (allMatches.size() > MAX_RESULTS) {
                        allMatches = new ArrayList<>(allMatches.subList(0, MAX_RESULTS));
                    }
                }
                
                // Check if we've reached the start of our time range
                if (batch.reachedStart) {
                    searchComplete = true;
                    break;
                }
            }
            
            // Build final response
            boolean truncated = allMatches.size() >= MAX_RESULTS || iteration >= MAX_SEARCH_ITERATIONS;
            SearchResult result = new SearchResult(allMatches, totalMessagesSearched, truncated);
            
            // Send results
            String finalMessage = formatSearchResults(result, query, exportRoomId, zoneId);
            matrixClient.updateTextMessage(responseRoomId, statusEventId, finalMessage);
            
        } catch (Exception e) {
            System.err.println("AI Search failed: " + e.getMessage());
            e.printStackTrace();
            matrixClient.sendText(responseRoomId, "❌ AI Search failed: " + e.getMessage());
        }
    }

    /**
     * A batch of messages fetched from the room
     */
    private static class BatchResult {
        final List<MessageInfo> messages;
        final String nextToken;
        final boolean reachedStart;

        BatchResult(List<MessageInfo> messages, String nextToken, boolean reachedStart) {
            this.messages = messages;
            this.nextToken = nextToken;
            this.reachedStart = reachedStart;
        }
    }

    /**
     * Information about a single message
     */
    private static class MessageInfo {
        final String eventId;
        final String sender;
        final long timestamp;
        final String body;
        final String msgtype;
        final String filename;

        MessageInfo(String eventId, String sender, long timestamp, String body, String msgtype, String filename) {
            this.eventId = eventId;
            this.sender = sender;
            this.timestamp = timestamp;
            this.body = body;
            this.msgtype = msgtype;
            this.filename = filename;
        }
    }

    /**
     * Fetch a batch of messages from the room
     */
    private BatchResult fetchMessageBatch(String roomId, String token, long startTime, long endTime, 
                                          ZoneId zoneId, AtomicBoolean abortFlag) {
        try {
            String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                    + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                    + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + "&dir=b&limit=100";
            
            HttpRequest msgReq = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            
            HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
            if (msgResp.statusCode() != 200) {
                System.err.println("Failed to fetch messages: " + msgResp.statusCode());
                return null;
            }
            
            JsonNode root = mapper.readTree(msgResp.body());
            JsonNode chunk = root.path("chunk");
            if (!chunk.isArray() || chunk.size() == 0) {
                return null;
            }
            
            List<MessageInfo> messages = new ArrayList<>();
            boolean reachedStart = false;
            
            for (JsonNode ev : chunk) {
                if (abortFlag != null && abortFlag.get()) {
                    return null;
                }
                
                String eventType = ev.path("type").asText(null);
                if (!"m.room.message".equals(eventType)) {
                    continue;
                }
                
                long originServerTs = ev.path("origin_server_ts").asLong(0);
                
                // Check time bounds
                if (originServerTs > endTime) {
                    continue;
                }
                if (startTime > 0 && originServerTs < startTime) {
                    reachedStart = true;
                    break;
                }
                
                String eventId = ev.path("event_id").asText(null);
                String sender = ev.path("sender").asText(null);
                String body = ev.path("content").path("body").asText(null);
                String msgtype = ev.path("content").path("msgtype").asText(null);
                String filename = ev.path("content").path("filename").asText(null);
                
                if (eventId != null && sender != null && body != null) {
                    messages.add(new MessageInfo(eventId, sender, originServerTs, body, msgtype, filename));
                }
            }
            
            String nextToken = reachedStart ? null : root.path("end").asText(null);
            return new BatchResult(messages, nextToken, reachedStart);
            
        } catch (Exception e) {
            System.err.println("Error fetching message batch: " + e.getMessage());
            return null;
        }
    }

    /**
     * Use AI to analyze a batch of messages and find matches for the query
     */
    private List<SearchMatch> analyzeBatchWithAI(List<MessageInfo> messages, String query, 
                                                  String roomId, ZoneId zoneId, AtomicBoolean abortFlag) {
        if (messages.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            // Build the prompt for AI analysis
            String prompt = buildSearchPrompt(messages, query, zoneId);
            
            // Call AI (prefer ArliAI, fallback to Cerebras)
            String aiResponse = callAI(prompt, abortFlag);
            
            if (aiResponse == null || aiResponse.isEmpty()) {
                return new ArrayList<>();
            }
            
            // Parse AI response to extract matches
            return parseAIResponse(aiResponse, messages, roomId, zoneId);
            
        } catch (Exception e) {
            System.err.println("AI analysis failed: " + e.getMessage());
            // Fallback: do simple keyword matching
            return performFallbackSearch(messages, query, zoneId);
        }
    }

    /**
     * Build the prompt for AI analysis
     */
    private String buildSearchPrompt(List<MessageInfo> messages, String query, ZoneId zoneId) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are helping a user find specific messages in a chat log. ");
        sb.append("The user is looking for: \"").append(query).append("\"\n\n");
        sb.append("Analyze the following messages and identify which ones match the user's search. ");
        sb.append("For media files (images, videos, audio, files), pay special attention to filenames and descriptions. ");
        sb.append("Return ONLY a JSON array of matches. Each match should have: ");
        sb.append("{\"index\": <number>, \"reason\": \"brief explanation of why it matches\"}\n\n");
        sb.append("Use the index number shown in brackets [0], [1], etc. for each message.\n");
        sb.append("If no matches are found, return an empty array: []\n\n");
        sb.append("Messages:\n");
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
        
        for (int i = 0; i < messages.size(); i++) {
            MessageInfo msg = messages.get(i);
            String timestamp = Instant.ofEpochMilli(msg.timestamp).atZone(zoneId).format(formatter);
            
            sb.append("[").append(i).append("] ");
            sb.append("[").append(timestamp).append("] ");
            sb.append("<").append(msg.sender).append("> ");
            
            if (msg.msgtype != null) {
                sb.append("[").append(msg.msgtype.toUpperCase()).append("] ");
            }
            if (msg.filename != null && !msg.filename.equals(msg.body)) {
                sb.append("File: ").append(msg.filename).append(" | ");
            }
            
            sb.append(msg.body).append("\n");
        }
        
        return sb.toString();
    }

    /**
     * Call AI API to analyze messages
     */
    private String callAI(String prompt, AtomicBoolean abortFlag) {
        // Try ArliAI first
        if (arliApiKey != null && !arliApiKey.isEmpty()) {
            try {
                return callArliAI(prompt, abortFlag);
            } catch (Exception e) {
                System.err.println("ArliAI failed, trying Cerebras: " + e.getMessage());
            }
        }
        
        // Fallback to Cerebras
        if (cerebrasApiKey != null && !cerebrasApiKey.isEmpty()) {
            try {
                return callCerebrasAI(prompt, abortFlag);
            } catch (Exception e) {
                System.err.println("Cerebras failed: " + e.getMessage());
            }
        }
        
        return null;
    }

    /**
     * Call ArliAI API
     */
    private String callArliAI(String prompt, AtomicBoolean abortFlag) throws Exception {
        if (arliApiKey == null || arliApiKey.isEmpty()) {
            throw new Exception("ARLI_API_KEY is not configured");
        }
        
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", "You are a helpful assistant that finds specific messages in chat logs. Return only JSON arrays."),
                Map.of("role", "user", "content", prompt)
        );
        
        Map<String, Object> payload = Map.of(
                "model", "Qwen3.5-27B-Derestricted",
                "messages", messages,
                "stream", false,
                "temperature", 0.1
        );
        
        String jsonPayload = mapper.writeValueAsString(payload);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.arliai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + arliApiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new Exception("ArliAI returned status " + response.statusCode());
        }
        
        JsonNode root = mapper.readTree(response.body());
        return root.path("choices").get(0).path("message").path("content").asText(null);
    }

    /**
     * Call Cerebras AI API
     */
    private String callCerebrasAI(String prompt, AtomicBoolean abortFlag) throws Exception {
        if (cerebrasApiKey == null || cerebrasApiKey.isEmpty()) {
            throw new Exception("CEREBRAS_API_KEY is not configured");
        }
        
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", "You are a helpful assistant that finds specific messages in chat logs. Return only JSON arrays."),
                Map.of("role", "user", "content", prompt)
        );
        
        Map<String, Object> payload = Map.of(
                "model", "qwen-3-235b-a22b-instruct-2507",
                "messages", messages,
                "stream", false,
                "temperature", 0.1
        );
        
        String jsonPayload = mapper.writeValueAsString(payload);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.cerebras.ai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cerebrasApiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new Exception("Cerebras returned status " + response.statusCode());
        }
        
        JsonNode root = mapper.readTree(response.body());
        return root.path("choices").get(0).path("message").path("content").asText(null);
    }

    /**
     * Parse AI response to extract search matches
     */
    private List<SearchMatch> parseAIResponse(String aiResponse, List<MessageInfo> messages, 
                                               String roomId, ZoneId zoneId) {
        List<SearchMatch> matches = new ArrayList<>();
        
        try {
            // Try to extract JSON array from the response
            String jsonStr = extractJsonArray(aiResponse);
            if (jsonStr == null) {
                return matches;
            }
            
            JsonNode array = mapper.readTree(jsonStr);
            if (!array.isArray()) {
                return matches;
            }
            
            for (JsonNode matchNode : array) {
                if (abortFlag()) break;
                
                int index = matchNode.path("index").asInt(-1);
                String reason = matchNode.path("reason").asText("");
                
                if (index >= 0 && index < messages.size()) {
                    MessageInfo msg = messages.get(index);
                    
                    // Determine media type
                    String mediaType = null;
                    if (msg.msgtype != null) {
                        switch (msg.msgtype) {
                            case "m.image": mediaType = "IMAGE"; break;
                            case "m.video": mediaType = "VIDEO"; break;
                            case "m.audio": mediaType = "AUDIO"; break;
                            case "m.file": mediaType = "FILE"; break;
                        }
                    }
                    
                    matches.add(new SearchMatch(
                            msg.eventId,
                            msg.sender,
                            msg.timestamp,
                            msg.body,
                            mediaType,
                            msg.filename,
                            reason
                    ));
                }
            }
            
        } catch (Exception e) {
            System.err.println("Failed to parse AI response: " + e.getMessage());
        }
        
        return matches;
    }

    /**
     * Extract JSON array from AI response (handles cases where AI adds extra text)
     */
    private String extractJsonArray(String response) {
        if (response == null) return null;
        
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        
        return null;
    }

    /**
     * Fallback search using simple keyword matching when AI is unavailable
     */
    private List<SearchMatch> performFallbackSearch(List<MessageInfo> messages, String query, ZoneId zoneId) {
        List<SearchMatch> matches = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        String[] queryTerms = lowerQuery.split("\\s+");
        
        for (MessageInfo msg : messages) {
            String lowerBody = msg.body.toLowerCase();
            String lowerFilename = msg.filename != null ? msg.filename.toLowerCase() : "";
            
            // Check if all query terms are present
            boolean allTermsFound = true;
            for (String term : queryTerms) {
                if (!lowerBody.contains(term) && !lowerFilename.contains(term)) {
                    allTermsFound = false;
                    break;
                }
            }
            
            if (allTermsFound) {
                String mediaType = null;
                if (msg.msgtype != null) {
                    switch (msg.msgtype) {
                        case "m.image": mediaType = "IMAGE"; break;
                        case "m.video": mediaType = "VIDEO"; break;
                        case "m.audio": mediaType = "AUDIO"; break;
                        case "m.file": mediaType = "FILE"; break;
                    }
                }
                
                matches.add(new SearchMatch(
                        msg.eventId,
                        msg.sender,
                        msg.timestamp,
                        msg.body,
                        mediaType,
                        msg.filename,
                        "Keyword match (AI unavailable)"
                ));
            }
        }
        
        return matches;
    }

    /**
     * Format search results for display
     */
    private String formatSearchResults(SearchResult result, String query, String roomId, ZoneId zoneId) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("\uD83D\uDD0D **AI Search Results**\n");
        sb.append("Query: \"").append(query).append("\"\n");
        sb.append("Searched ").append(result.totalMessagesSearched).append(" messages\n\n");
        
        if (result.error != null) {
            sb.append("⚠️ Error: ").append(result.error).append("\n\n");
        }
        
        if (result.matches.isEmpty()) {
            sb.append("No matches found.\n");
            if (result.totalMessagesSearched == 0) {
                sb.append("No messages were available to search.\n");
            }
            return sb.toString();
        }
        
        sb.append("Found ").append(result.matches.size()).append(" match(es)");
        if (result.truncated) {
            sb.append(" (results truncated)");
        }
        sb.append(":\n\n");
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
        
        for (int i = 0; i < result.matches.size(); i++) {
            SearchMatch match = result.matches.get(i);
            String timestamp = Instant.ofEpochMilli(match.timestamp).atZone(zoneId).format(formatter);
            
            sb.append("**").append(i + 1).append(".** ");
            
            if (match.mediaType != null) {
                sb.append("[").append(match.mediaType).append("] ");
            }
            
            sb.append("<").append(match.sender).append("> ");
            sb.append("[").append(timestamp).append("]\n");
            
            if (match.filename != null && !match.filename.equals(match.body)) {
                sb.append("   File: ").append(match.filename).append("\n");
            }
            
            // Truncate body for display
            String displayBody = match.body;
            if (displayBody.length() > 200) {
                displayBody = displayBody.substring(0, 197) + "...";
            }
            sb.append("   ").append(displayBody).append("\n");
            
            if (match.matchReason != null && !match.matchReason.isEmpty()) {
                sb.append("   _Reason: ").append(match.matchReason).append("_\n");
            }
            
            // Add message link
            String messageLink = "https://matrix.to/#/" + roomId + "/" + match.eventId;
            sb.append("   ").append(messageLink).append("\n\n");
        }
        
        return sb.toString();
    }

    /**
     * Get pagination token from sync
     */
    private String getPaginationToken(String roomId, String providedToken) {
        if (providedToken != null) {
            return providedToken;
        }
        
        try {
            String filter = "{\"room\":{\"rooms\":[\"" + roomId + "\"],\"timeline\":{\"limit\":1},\"state\":{\"lazy_load_members\":true}},\"presence\":{\"not_types\":[\"m.presence\"]}}";
            String syncUrl = homeserverUrl + "/_matrix/client/v3/sync?timeout=0&filter=" + 
                    URLEncoder.encode(filter, StandardCharsets.UTF_8);
            
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(syncUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            
            HttpResponse<String> syncResp = httpClient.send(syncReq, HttpResponse.BodyHandlers.ofString());
            if (syncResp.statusCode() == 200) {
                JsonNode root = mapper.readTree(syncResp.body());
                JsonNode roomNode = root.path("rooms").path("join").path(roomId);
                if (!roomNode.isMissingNode()) {
                    return roomNode.path("timeline").path("prev_batch").asText(null);
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting pagination token: " + e.getMessage());
        }
        
        return null;
    }

    /**
     * Check abort flag (placeholder - will be set by caller)
     */
    private AtomicBoolean abortFlag = new AtomicBoolean(false);
    
    private boolean abortFlag() {
        return abortFlag.get();
    }
}