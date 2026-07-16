package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles text search operations (grep, search)
 */
public class TextSearchService {
    private final MatrixClient matrixClient;
    private final RoomHistoryManager historyManager;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final MatrixRobobot.Config config;
    private final Map<String, AtomicBoolean> runningOperations;
    private final Map<String, TextSearchPaginationState> searchCache;

    public TextSearchService(MatrixClient matrixClient, RoomHistoryManager historyManager, HttpClient httpClient,
            ObjectMapper mapper, String homeserverUrl, MatrixRobobot.Config config,
            Map<String, AtomicBoolean> runningOperations) {
        this.matrixClient = matrixClient;
        this.historyManager = historyManager;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.config = config;
        this.runningOperations = runningOperations;
        this.searchCache = new ConcurrentHashMap<>();
    }

    public void performGrep(String roomId, String sender, String responseRoomId, String exportRoomId, int hours,
            String fromToken, String pattern, ZoneId zoneId) {
        try {
            // Register this operation for abort capability
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            String timeInfo = "last " + hours + "h";

            // Send initial message and get event ID for updates
            String initialMessage = "Performing grep search in " + exportRoomId + " for: \"" + pattern + "\" ("
                    + timeInfo + ")...";
            String eventMessageId = matrixClient.sendNoticeWithEventId(responseRoomId, initialMessage);
            String originalEventId = eventMessageId; // Track the original event ID for all updates

            // Calculate the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            // If we don't have a pagination token, try to get one via a short sync
            String token = fromToken;
            if (token == null) {
                try {
                    String filter = "{\"room\":{\"rooms\":[\"" + exportRoomId + "\"],\"timeline\":{\"limit\":1},\"state\":{\"lazy_load_members\":true}},\"presence\":{\"not_types\":[\"m.presence\"]}}";
                    HttpRequest syncReq = HttpRequest.newBuilder()
                            .uri(URI.create(homeserverUrl + "/_matrix/client/v3/sync?timeout=0&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)))
                            .header("Authorization", "Bearer " + config.accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> syncResp = httpClient.send(syncReq, HttpResponse.BodyHandlers.ofString());
                    if (syncResp.statusCode() == 200) {
                        JsonNode root = mapper.readTree(syncResp.body());
                        token = root.path("next_batch").asText(null);
                    } else {
                        System.out.println("Sync returned " + syncResp.statusCode() + " for grep pagination token");
                    }
                } catch (Exception e) {
                    System.out.println("Sync failed getting pagination token for grep: " + e.getMessage());
                }
            }

            if (token == null) {
                System.out.println("No pagination token available for grep — results will be empty");
            }

            java.util.List<String> results = new java.util.ArrayList<>();
            java.util.List<String> eventIds = new java.util.ArrayList<>();
            int maxResults = 100;
            boolean truncated = false;

            // Case-insensitive literal pattern matching
            String lowerPattern = pattern.toLowerCase();
            long lastUpdateTime = 0;
            int lastResultCount = 0;

            while (token != null && results.size() < maxResults) {
                // Check for abort signal
                if (abortFlag.get()) {
                    System.out.println("Grep search aborted by user: " + sender);
                    runningOperations.remove(sender);
                    return;
                }

                try {
                    String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                            + URLEncoder.encode(exportRoomId, StandardCharsets.UTF_8)
                            + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                            + "&dir=b&limit=1000";
                    HttpRequest msgReq = HttpRequest.newBuilder()
                            .uri(URI.create(messagesUrl))
                            .header("Authorization", "Bearer " + config.accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
                    if (msgResp.statusCode() != 200) {
                        System.out
                                .println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                        break;
                    }
                    JsonNode root = mapper.readTree(msgResp.body());
                    JsonNode chunk = root.path("chunk");
                    if (!chunk.isArray() || chunk.size() == 0)
                        break;

                    boolean reachedStart = false;
                    for (JsonNode ev : chunk) {
                        // Check for abort signal inside the loop too
                        if (abortFlag.get()) {
                            System.out.println("Grep search aborted by user: " + sender);
                            runningOperations.remove(sender);
                            return;
                        }

                        if (!"m.room.message".equals(ev.path("type").asText(null)))
                            continue;
                        long originServerTs = ev.path("origin_server_ts").asLong(0);

                        if (originServerTs > endTime) {
                            continue; // Skip messages newer than our range
                        }

                        if (originServerTs < startTime) {
                            reachedStart = true;
                            break; // Stop when we reach messages older than start time
                        }

                        String body = ev.path("content").path("body").asText(null);
                        String senderMsg = ev.path("sender").asText(null);
                        String eventId = ev.path("event_id").asText(null);
                        if (body != null && senderMsg != null && eventId != null) {
                            // Format timestamp with timezone (convert UTC to user's timezone)
                            String timestamp = java.time.Instant.ofEpochMilli(originServerTs)
                                    .atZone(zoneId)
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                            String formattedLog = "[" + timestamp + "] <" + senderMsg + "> " + body;

                            // Case-insensitive literal search on the formatted log line
                            if (formattedLog.toLowerCase().contains(lowerPattern)) {
                                results.add(formattedLog);
                                eventIds.add(eventId);

                                // Update message every every 3 seconds
                                if (eventMessageId != null && (System.currentTimeMillis() - lastUpdateTime > 3000)) {
                                    StringBuilder updateMsg = new StringBuilder();
                                    updateMsg.append("Grep results for \"").append(pattern).append("\" - ");
                                    updateMsg.append("from last ").append(hours).append(" hours. ");
                                    updateMsg.append(results.size()).append(" matches (searching...)\n");
                                    for (int i = 0; i < results.size(); i++) {
                                        updateMsg.append(results.get(i)).append(" ");
                                        String messageLink = "https://matrix.to/#/" + exportRoomId + "/"
                                                + eventIds.get(i);
                                        updateMsg.append(messageLink).append("\n");
                                    }
                                    // Always use original event ID for updates
                                    matrixClient.updateNoticeMessage(responseRoomId, originalEventId,
                                            updateMsg.toString());
                                    lastUpdateTime = System.currentTimeMillis();
                                    lastResultCount = results.size();
                                }

                                if (results.size() >= maxResults) {
                                    truncated = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (reachedStart) {
                        break; // We've collected all messages in our time range
                    }

                    if (token != null) {
                        token = root.path("end").asText(null);
                    }

                } catch (Exception e) {
                    System.out.println("Error during grep search: " + e.getMessage());
                    break;
                }
            }

            if (results.isEmpty()) {
                if (originalEventId != null) {
                    matrixClient.updateTextMessage(responseRoomId, originalEventId, "No matches found for pattern: \""
                            + pattern + "\" in " + timeInfo + " of " + exportRoomId + ".");
                } else {
                    matrixClient.sendText(responseRoomId, "No matches found for pattern: \"" + pattern + "\" in "
                            + timeInfo + " of " + exportRoomId + ".");
                }
                runningOperations.remove(sender);
                return;
            }

            // Final update with complete results
            StringBuilder response = new StringBuilder();
            response.append("Grep results for \"").append(pattern).append("\" - ");
            response.append("from last ").append(hours).append(" hours. ");
            response.append(results.size()).append(" matches.\n");

            for (int i = 0; i < results.size(); i++) {
                response.append(results.get(i)).append(" ");
                // Add message link
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + eventIds.get(i);
                response.append(messageLink).append("\n");
            }

            if (originalEventId != null) {
                matrixClient.updateTextMessage(responseRoomId, originalEventId, response.toString());
            } else {
                matrixClient.sendText(responseRoomId, response.toString());
            }

            runningOperations.remove(sender);

        } catch (Exception e) {
            System.out.println("Failed to perform grep: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Error performing grep: " + e.getMessage());
            runningOperations.remove(sender);
        }
    }

    public void performGrepSlow(String roomId, String sender, String responseRoomId, String exportRoomId, int hours,
            String fromToken, String pattern, ZoneId zoneId) {
        try {
            // Register this operation for abort capability
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            String timeInfo = "last " + hours + "h";

            matrixClient.sendNotice(responseRoomId, "Performing slow grep search in " + exportRoomId + " for: \""
                    + pattern + "\" (" + timeInfo + ")...");

            // Calculate the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            // Use existing fetchRoomHistoryWithIds to get all messages first
            RoomHistoryManager.ChatLogsWithIds result = historyManager.fetchRoomHistoryWithIds(exportRoomId, hours,
                    fromToken, startTime, endTime, zoneId, abortFlag);

            // Check for abort after fetch
            if (abortFlag.get()) {
                System.out.println("Grep-slow aborted by user: " + sender);
                runningOperations.remove(sender);
                return;
            }

            if (result.logs.isEmpty()) {
                matrixClient.sendText(responseRoomId,
                        "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                runningOperations.remove(sender);
                return;
            }

            // Case-insensitive literal pattern matching
            String lowerPattern = pattern.toLowerCase();
            java.util.List<String> results = new java.util.ArrayList<>();
            java.util.List<String> eventIds = new java.util.ArrayList<>();

            for (int i = 0; i < result.logs.size(); i++) {
                // Check for abort during processing
                if (abortFlag.get()) {
                    System.out.println("Grep-slow aborted by user: " + sender);
                    runningOperations.remove(sender);
                    return;
                }

                String log = result.logs.get(i);
                String eventId = result.eventIds.get(i);

                // Case-insensitive literal search
                if (log.toLowerCase().contains(lowerPattern)) {
                    results.add(log);
                    eventIds.add(eventId);
                }
            }

            if (results.isEmpty()) {
                matrixClient.sendText(responseRoomId, "No matches found for pattern: \"" + pattern + "\" in " + timeInfo
                        + " of " + exportRoomId + ".");
                runningOperations.remove(sender);
                return;
            }

            // Format results
            StringBuilder response = new StringBuilder();
            response.append("Grep-Slow results for \"").append(pattern).append("\" - ");
            response.append("from last ").append(hours).append(" hours. ");
            response.append(results.size()).append(" matches.\n");

            for (int i = 0; i < results.size(); i++) {
                response.append(results.get(i)).append(" ");
                // Add message link
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + eventIds.get(i);
                response.append(messageLink).append("\n");
            }

            matrixClient.sendText(responseRoomId, response.toString());
            runningOperations.remove(sender);

        } catch (Exception e) {
            System.out.println("Failed to perform grep-slow: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Error performing grep-slow: " + e.getMessage());
            runningOperations.remove(sender);
        }
    }

    public void performSearch(String roomId, String sender, String responseRoomId, String exportRoomId, int hours,
            String fromToken, String query, ZoneId zoneId) {
        try {
            String timeInfo = "last " + hours + "h";

            String initialMessage = "Performing search in " + exportRoomId + " for: \"" + query + "\" (" + timeInfo
                    + ")...";
            String eventMessageId = matrixClient.sendNoticeWithEventId(responseRoomId, initialMessage);
            String originalEventId = eventMessageId;

            String[] searchTerms = query.toLowerCase().split("\\s+");

            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            TextSearchPaginationState state = new TextSearchPaginationState(sender, query, searchTerms,
                    exportRoomId, responseRoomId, originalEventId, zoneId, startTime, endTime);

            fetchMoreResults(state);

            if (state.allResults.isEmpty()) {
                if (originalEventId != null) {
                    matrixClient.updateTextMessage(responseRoomId, originalEventId,
                            "No messages found containing all terms: \"" + query + "\" in " + timeInfo + " of "
                                    + exportRoomId + ".");
                } else {
                    matrixClient.sendText(responseRoomId, "No messages found containing all terms: \"" + query
                            + "\" in " + timeInfo + " of " + exportRoomId + ".");
                }
                return;
            }

            searchCache.put(sender, state);

            matrixClient.updateNoticeMessage(responseRoomId, originalEventId, state.renderPage());

        } catch (Exception e) {
            System.out.println("Failed to perform search: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Error performing search: " + e.getMessage());
        }
    }

    public boolean goToPage(String sender, int pageNum) {
        TextSearchPaginationState state = searchCache.get(sender);
        if (state == null) return false;

        int targetPage = ensurePageLoaded(state, pageNum);
        if (targetPage < 1) return false;

        if (state.goToPage(targetPage)) {
            matrixClient.updateNoticeMessage(state.responseRoomId, state.eventMessageId, state.renderPage());
            return true;
        }
        return false;
    }

    private int ensurePageLoaded(TextSearchPaginationState state, int pageNum) {
        if (pageNum < 1) return -1;

        boolean needsMore = pageNum > state.getTotalPages()
                || (pageNum == state.getTotalPages() && state.hasMoreResults);
        if (!needsMore) return pageNum;

        matrixClient.updateTextMessage(state.responseRoomId, state.eventMessageId,
                "Loading more search results for page " + pageNum + "...");

        while (pageNum > state.getTotalPages() || (pageNum == state.getTotalPages() && state.hasMoreResults)) {
            if (!state.hasMoreResults && pageNum > state.getTotalPages()) break;
            try {
                fetchMoreResults(state);
            } catch (Exception e) {
                System.out.println("Failed to load more search results: " + e.getMessage());
                return -1;
            }
            if (!state.hasMoreResults) break;
        }

        if (pageNum > state.getTotalPages()) {
            pageNum = state.getTotalPages();
        }
        return pageNum;
    }

    private void fetchMoreResults(TextSearchPaginationState state) throws Exception {
        if (state.reachedStart) {
            state.hasMoreResults = false;
            return;
        }

        String token = state.nextBatch;
        if (token == null) {
            String filter = "{\"room\":{\"rooms\":[\"" + state.exportRoomId + "\"],\"timeline\":{\"limit\":1},\"state\":{\"lazy_load_members\":true}},\"presence\":{\"not_types\":[\"m.presence\"]}}";
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(homeserverUrl + "/_matrix/client/v3/sync?timeout=0&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + config.accessToken)
                    .GET()
                    .build();
            HttpResponse<String> syncResp = httpClient.send(syncReq, HttpResponse.BodyHandlers.ofString());
            if (syncResp.statusCode() == 200) {
                JsonNode root = mapper.readTree(syncResp.body());
                token = root.path("next_batch").asText(null);
            }
            if (token == null) {
                state.hasMoreResults = false;
                System.out.println("No pagination token available for search continuation");
                return;
            }
        }

        String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                + URLEncoder.encode(state.exportRoomId, StandardCharsets.UTF_8)
                + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                + "&dir=b&limit=1000";
        HttpRequest msgReq = HttpRequest.newBuilder()
                .uri(URI.create(messagesUrl))
                .header("Authorization", "Bearer " + config.accessToken)
                .GET()
                .build();
        HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
        if (msgResp.statusCode() != 200) {
            System.out.println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
            state.hasMoreResults = false;
            return;
        }

        JsonNode root = mapper.readTree(msgResp.body());
        JsonNode chunk = root.path("chunk");
        if (!chunk.isArray() || chunk.size() == 0) {
            state.hasMoreResults = false;
            return;
        }

        for (JsonNode ev : chunk) {
            if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
            long originServerTs = ev.path("origin_server_ts").asLong(0);

            if (originServerTs > state.endTime) continue;
            if (originServerTs < state.startTime) {
                state.reachedStart = true;
                state.hasMoreResults = false;
                break;
            }

            String body = ev.path("content").path("body").asText(null);
            String senderMsg = ev.path("sender").asText(null);
            String eventId = ev.path("event_id").asText(null);
            if (body == null || senderMsg == null || eventId == null) continue;

            if (!state.seenEventIds.add(eventId)) continue;

            String timestamp = java.time.Instant.ofEpochMilli(originServerTs)
                    .atZone(state.zoneId)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            String formattedLog = "[" + timestamp + "] <" + senderMsg + "> " + body;

            String lowerLog = formattedLog.toLowerCase();
            boolean allTermsFound = true;
            for (String term : state.searchTerms) {
                if (!lowerLog.contains(term)) {
                    allTermsFound = false;
                    break;
                }
            }

            if (allTermsFound) {
                state.allResults.add(new TextSearchHit(formattedLog, eventId));
            }
        }

        if (!state.reachedStart) {
            state.nextBatch = root.path("end").asText(null);
            state.hasMoreResults = state.nextBatch != null;
        }
    }

    public void performMediaSearch(String roomId, String sender, String responseRoomId, String exportRoomId, int hours,
            String fromToken, String query, ZoneId zoneId) {
        try {
            // Register this operation for abort capability
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            String timeInfo = "last " + hours + "h";

            // Send initial message and get event ID for updates
            String initialMessage = "Performing media search in " + exportRoomId + " for: \"" + query + "\" (" + timeInfo
                    + ")...";
            String eventMessageId = matrixClient.sendNoticeWithEventId(responseRoomId, initialMessage);
            String originalEventId = eventMessageId; // Track the original event ID for all updates

            // Calculate the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            // If we don't have a pagination token, try to get one via a short sync
            String token = fromToken;
            if (token == null) {
                try {
                    String filter = "{\"room\":{\"rooms\":[\"" + exportRoomId + "\"],\"timeline\":{\"limit\":1},\"state\":{\"lazy_load_members\":true}},\"presence\":{\"not_types\":[\"m.presence\"]}}";
                    HttpRequest syncReq = HttpRequest.newBuilder()
                            .uri(URI.create(homeserverUrl + "/_matrix/client/v3/sync?timeout=0&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)))
                            .header("Authorization", "Bearer " + config.accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> syncResp = httpClient.send(syncReq, HttpResponse.BodyHandlers.ofString());
                    if (syncResp.statusCode() == 200) {
                        JsonNode root = mapper.readTree(syncResp.body());
                        token = root.path("next_batch").asText(null);
                    } else {
                        System.out.println("Sync returned " + syncResp.statusCode() + " for media search pagination token");
                    }
                } catch (Exception e) {
                    System.out.println("Sync failed getting pagination token for media search: " + e.getMessage());
                }
            }

            if (token == null) {
                System.out.println("No pagination token available for media search — results will be empty");
            }

            // Parse search terms (space-separated, case-insensitive)
            String[] searchTerms = query.toLowerCase().split("\\s+");

            java.util.List<String> results = new java.util.ArrayList<>();
            java.util.List<String> eventIds = new java.util.ArrayList<>();
            int maxResults = 100;
            boolean truncated = false;
            long lastUpdateTime = 0;
            int lastResultCount = 0;

            while (token != null && results.size() < maxResults) {
                // Check for abort signal
                if (abortFlag.get()) {
                    System.out.println("Media search aborted by user: " + sender);
                    runningOperations.remove(sender);
                    return;
                }

                try {
                    String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                            + URLEncoder.encode(exportRoomId, StandardCharsets.UTF_8)
                            + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                            + "&dir=b&limit=1000";
                    HttpRequest msgReq = HttpRequest.newBuilder()
                            .uri(URI.create(messagesUrl))
                            .header("Authorization", "Bearer " + config.accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
                    if (msgResp.statusCode() != 200) {
                        System.out
                                .println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                        break;
                    }
                    JsonNode root = mapper.readTree(msgResp.body());
                    JsonNode chunk = root.path("chunk");
                    if (!chunk.isArray() || chunk.size() == 0)
                        break;

                    boolean reachedStart = false;
                    for (JsonNode ev : chunk) {
                        // Check for abort signal inside the loop too
                        if (abortFlag.get()) {
                            System.out.println("Media search aborted by user: " + sender);
                            runningOperations.remove(sender);
                            return;
                        }

                        // Check for media message types
                        String eventType = ev.path("type").asText(null);
                        String msgtype = ev.path("content").path("msgtype").asText(null);
                        
                        boolean isMediaMessage = false;
                        String mediaType = "";
                        
                        if ("m.room.message".equals(eventType)) {
                            if ("m.file".equals(msgtype) || "m.image".equals(msgtype) || 
                                "m.video".equals(msgtype) || "m.audio".equals(msgtype)) {
                                isMediaMessage = true;
                                mediaType = msgtype.substring(2).toUpperCase(); // Remove "m." prefix
                            }
                        } else if ("m.room.encrypted".equals(eventType)) {
                            // Encrypted messages may contain media - we'll include them
                            isMediaMessage = true;
                            mediaType = "ENCRYPTED";
                        }

                        if (!isMediaMessage)
                            continue;

                        long originServerTs = ev.path("origin_server_ts").asLong(0);

                        if (originServerTs > endTime) {
                            continue; // Skip messages newer than our range
                        }

                        if (originServerTs < startTime) {
                            reachedStart = true;
                            break; // Stop when we reach messages older than start time
                        }

                        String body = ev.path("content").path("body").asText(null);
                        String senderMsg = ev.path("sender").asText(null);
                        String eventId = ev.path("event_id").asText(null);
                        
                        // Extract filename for media messages
                        String filename = null;
                        if ("m.room.message".equals(eventType)) {
                            filename = ev.path("content").path("filename").asText(null); // For file messages, filename field contains actual filename
                            if (filename == null) {
                                // For some media types, filename might be in body, but we'll use body as caption
                                filename = ev.path("content").path("body").asText(null);
                            }
                        } else if ("m.room.encrypted".equals(eventType)) {
                            filename = ev.path("content").path("filename").asText(null); // For encrypted media
                        }

                        if (body != null && senderMsg != null && eventId != null) {
                            // Format timestamp with timezone (convert UTC to user's timezone)
                            String timestamp = java.time.Instant.ofEpochMilli(originServerTs)
                                    .atZone(zoneId)
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                            
                            // Build the formatted log with media type and filename
                            StringBuilder formattedLogBuilder = new StringBuilder();
                            formattedLogBuilder.append("[").append(timestamp).append("] <").append(senderMsg).append("> ");
                            
                            if (filename != null && !filename.equals(body)) {
                                formattedLogBuilder.append("[").append(mediaType).append(": ").append(filename).append("] ");
                                // Include filename in the search by appending it to the body for search purposes
                                body = body + " " + filename;
                            } else {
                                formattedLogBuilder.append("[").append(mediaType).append("] ");
                            }
                            formattedLogBuilder.append(body);
                            
                            String formattedLog = formattedLogBuilder.toString();

                            // Check if formatted log contains ALL search terms (case-insensitive)
                            String lowerLog = formattedLog.toLowerCase();
                            boolean allTermsFound = true;
                            for (String term : searchTerms) {
                                if (!lowerLog.contains(term)) {
                                    allTermsFound = false;
                                    break;
                                }
                            }

                            if (allTermsFound) {
                                results.add(formattedLog);
                                eventIds.add(eventId);

                                // Update message every 3 seconds
                                    if (eventMessageId != null && (System.currentTimeMillis() - lastUpdateTime > 3000)) {
                                    StringBuilder updateMsg = new StringBuilder();
                                    updateMsg.append("Media search results for \"").append(query).append("\" - ");
                                    updateMsg.append("from last ").append(hours).append(" hours. ");
                                    updateMsg.append(results.size()).append(" matches (searching...)\n");
                                    for (int i = 0; i < results.size(); i++) {
                                        updateMsg.append(results.get(i)).append(" ");
                                        String messageLink = "https://matrix.to/#/" + exportRoomId + "/"
                                                + eventIds.get(i);
                                        updateMsg.append(messageLink).append("\n");
                                    }
                                    // Always use original event ID for updates
                                    matrixClient.updateNoticeMessage(responseRoomId, originalEventId,
                                            updateMsg.toString());
                                    lastUpdateTime = System.currentTimeMillis();
                                    lastResultCount = results.size();
                                }

                                if (results.size() >= maxResults) {
                                    truncated = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (reachedStart) {
                        break; // We've collected all messages in our time range
                    }

                    if (token != null) {
                        token = root.path("end").asText(null);
                    }

                } catch (Exception e) {
                    System.out.println("Error during media search: " + e.getMessage());
                    break;
                }
            }

            if (results.isEmpty()) {
                if (originalEventId != null) {
                    matrixClient.updateTextMessage(responseRoomId, originalEventId,
                            "No media messages found containing all terms: \"" + query + "\" in " + timeInfo + " of "
                                    + exportRoomId + ".");
                } else {
                    matrixClient.sendText(responseRoomId, "No media messages found containing all terms: \"" + query
                            + "\" in " + timeInfo + " of " + exportRoomId + ".");
                }
                runningOperations.remove(sender);
                return;
            }

            // Final update with complete results
            StringBuilder response = new StringBuilder();
            response.append("Media search results for \"").append(query).append("\" - ");
            response.append("from last ").append(hours).append(" hours. ");
            response.append(results.size()).append(" matches.");
            if (truncated) {
                response.append(" (there may be more.)");
            }
            response.append("\n");

            for (int i = 0; i < results.size(); i++) {
                response.append(results.get(i)).append(" ");
                // Add message link
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + eventIds.get(i);
                response.append(messageLink).append("\n");
            }

            if (originalEventId != null) {
                matrixClient.updateTextMessage(responseRoomId, originalEventId, response.toString());
            } else {
                matrixClient.sendText(responseRoomId, response.toString());
            }

            runningOperations.remove(sender);

        } catch (Exception e) {
            System.out.println("Failed to perform media search: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Error performing media search: " + e.getMessage());
            runningOperations.remove(sender);
        }
    }

    private static record TextSearchHit(String formattedLog, String eventId) {}

    private static class TextSearchPaginationState {
        static final int PAGE_SIZE = 25;

        final List<TextSearchHit> allResults = new ArrayList<>();
        final Set<String> seenEventIds = new HashSet<>();
        int currentPage;
        final String sender;
        final String query;
        final String[] searchTerms;
        final String exportRoomId;
        final String responseRoomId;
        final String eventMessageId;
        final ZoneId zoneId;
        final long startTime;
        final long endTime;

        String nextBatch;
        boolean reachedStart;
        boolean hasMoreResults;

        TextSearchPaginationState(String sender, String query, String[] searchTerms,
                String exportRoomId, String responseRoomId, String eventMessageId,
                ZoneId zoneId, long startTime, long endTime) {
            this.sender = sender;
            this.query = query;
            this.searchTerms = searchTerms;
            this.exportRoomId = exportRoomId;
            this.responseRoomId = responseRoomId;
            this.eventMessageId = eventMessageId;
            this.zoneId = zoneId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.currentPage = 0;
            this.nextBatch = null;
            this.reachedStart = false;
            this.hasMoreResults = true;
        }

        int getTotalPages() {
            return (int) Math.ceil((double) allResults.size() / PAGE_SIZE);
        }

        List<TextSearchHit> getCurrentPageHits() {
            int fromIndex = currentPage * PAGE_SIZE;
            int toIndex = Math.min(fromIndex + PAGE_SIZE, allResults.size());
            if (fromIndex >= allResults.size()) return List.of();
            return allResults.subList(fromIndex, toIndex);
        }

        String renderPage() {
            List<TextSearchHit> pageHits = getCurrentPageHits();
            StringBuilder sb = new StringBuilder();
            sb.append("Search results for \"").append(query).append("\"");
            sb.append(" from last ").append((endTime - startTime) / 3600000).append(" hours in ");
            sb.append(exportRoomId).append("\n");
            sb.append("Page ").append(currentPage + 1).append("/");
            if (hasMoreResults) {
                sb.append("?");
            } else {
                sb.append(getTotalPages());
            }
            sb.append(" (");
            if (hasMoreResults) {
                sb.append("over ").append(allResults.size()).append(" results");
            } else {
                sb.append(allResults.size()).append(" total matches");
            }
            sb.append(")\n\n");

            for (TextSearchHit hit : pageHits) {
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + hit.eventId();
                sb.append(hit.formattedLog()).append(" ").append(messageLink).append("\n");
            }

            sb.append("\n");
            if (hasMoreResults || getTotalPages() > 1) {
                sb.append("Use !page <n> to navigate pages");
                if (!hasMoreResults) {
                    sb.append(" (1-").append(getTotalPages()).append(")");
                }
                sb.append(".");
            }
            return sb.toString().trim();
        }

        boolean goToPage(int pageNum) {
            if (pageNum < 1 || pageNum > getTotalPages()) return false;
            currentPage = pageNum - 1;
            return true;
        }
    }
}
