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
import java.util.Map;
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
    }

    public void performGrep(String roomId, String sender, String responseRoomId, String exportRoomId, int hours,
            String fromToken, String pattern, String timezoneAbbr) {
        try {
            // Register this operation for abort capability
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            ZoneId zoneId = getZoneIdFromAbbr(timezoneAbbr);
            String timeInfo = "last " + hours + "h";

            // Send initial message and get event ID for updates
            String initialMessage = "Performing grep search in " + exportRoomId + " for: \"" + pattern + "\" ("
                    + timeInfo + ")...";
            String eventMessageId = matrixClient.sendTextWithEventId(responseRoomId, initialMessage);
            String originalEventId = eventMessageId; // Track the original event ID for all updates

            // Calculate the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            // If we don't have a pagination token, try to get one via a short sync
            String token = fromToken;
            if (token == null) {
                try {
                    HttpRequest syncReq = HttpRequest.newBuilder()
                            .uri(URI.create(homeserverUrl + "/_matrix/client/v3/sync?timeout=0"))
                            .header("Authorization", "Bearer " + config.accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> syncResp = httpClient.send(syncReq, HttpResponse.BodyHandlers.ofString());
                    if (syncResp.statusCode() == 200) {
                        JsonNode root = mapper.readTree(syncResp.body());
                        JsonNode roomNode = root.path("rooms").path("join").path(exportRoomId);
                        if (!roomNode.isMissingNode()) {
                            token = roomNode.path("timeline").path("prev_batch").asText(null);
                        }
                    }
                } catch (Exception ignore) {
                    // ignore errors here, we'll just start fetching from the latest available if
                    // sync fails
                }
            }

            java.util.List<String> results = new java.util.ArrayList<>();
            java.util.List<String> eventIds = new java.util.ArrayList<>();
            int maxResults = 50;
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
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                            String formattedLog = "[" + timestamp + "] <" + senderMsg + "> " + body;

                            // Case-insensitive literal search on the formatted log line
                            if (formattedLog.toLowerCase().contains(lowerPattern)) {
                                results.add(formattedLog);
                                eventIds.add(eventId);

                                // Update message every 5 results or every 2 seconds
                                if (eventMessageId != null && (results.size() - lastResultCount >= 5
                                        || System.currentTimeMillis() - lastUpdateTime > 2000)) {
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
                                    matrixClient.updateTextMessage(responseRoomId, originalEventId,
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
            response.append(results.size()).append(" matches.");
            if (truncated) {
                response.append(" (there may be more, use !grep-slow to see all results.)");
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
            System.out.println("Failed to perform grep: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Error performing grep: " + e.getMessage());
            runningOperations.remove(sender);
        }
    }

    public void performGrepSlow(String roomId, String sender, String responseRoomId, String exportRoomId, int hours,
            String fromToken, String pattern, String timezoneAbbr) {
        try {
            // Register this operation for abort capability
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            ZoneId zoneId = getZoneIdFromAbbr(timezoneAbbr);
            String timeInfo = "last " + hours + "h";

            matrixClient.sendText(responseRoomId, "Performing slow grep search in " + exportRoomId + " for: \""
                    + pattern + "\" (" + timeInfo + ")...");

            // Calculate the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            // Use existing fetchRoomHistoryWithIds to get all messages first
            // Note: This method doesn't support abort during fetch, but we can check after
            RoomHistoryManager.ChatLogsWithIds result = historyManager.fetchRoomHistoryWithIds(exportRoomId, hours,
                    fromToken, startTime, endTime, zoneId);

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
            String fromToken, String query, String timezoneAbbr) {
        try {
            // Register this operation for abort capability
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);

            ZoneId zoneId = getZoneIdFromAbbr(timezoneAbbr);
            String timeInfo = "last " + hours + "h";

            // Send initial message and get event ID for updates
            String initialMessage = "Performing search in " + exportRoomId + " for: \"" + query + "\" (" + timeInfo
                    + ")...";
            String eventMessageId = matrixClient.sendTextWithEventId(responseRoomId, initialMessage);
            String originalEventId = eventMessageId; // Track the original event ID for all updates

            // Calculate the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            // If we don't have a pagination token, try to get one via a short sync
            String token = fromToken;
            if (token == null) {
                try {
                    HttpRequest syncReq = HttpRequest.newBuilder()
                            .uri(URI.create(homeserverUrl + "/_matrix/client/v3/sync?timeout=0"))
                            .header("Authorization", "Bearer " + config.accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> syncResp = httpClient.send(syncReq, HttpResponse.BodyHandlers.ofString());
                    if (syncResp.statusCode() == 200) {
                        JsonNode root = mapper.readTree(syncResp.body());
                        JsonNode roomNode = root.path("rooms").path("join").path(exportRoomId);
                        if (!roomNode.isMissingNode()) {
                            token = roomNode.path("timeline").path("prev_batch").asText(null);
                        }
                    }
                } catch (Exception ignore) {
                    // ignore errors here, we'll just start fetching from the latest available if
                    // sync fails
                }
            }

            // Parse search terms (space-separated, case-insensitive)
            String[] searchTerms = query.toLowerCase().split("\\s+");

            java.util.List<String> results = new java.util.ArrayList<>();
            java.util.List<String> eventIds = new java.util.ArrayList<>();
            int maxResults = 50;
            boolean truncated = false;
            long lastUpdateTime = 0;
            int lastResultCount = 0;

            while (token != null && results.size() < maxResults) {
                // Check for abort signal
                if (abortFlag.get()) {
                    System.out.println("Search aborted by user: " + sender);
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
                            System.out.println("Search aborted by user: " + sender);
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
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                            String formattedLog = "[" + timestamp + "] <" + senderMsg + "> " + body;

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

                                // Update message every 5 results or every 2 seconds
                                if (eventMessageId != null && (results.size() - lastResultCount >= 5
                                        || System.currentTimeMillis() - lastUpdateTime > 2000)) {
                                    StringBuilder updateMsg = new StringBuilder();
                                    updateMsg.append("Search results for \"").append(query).append("\" - ");
                                    updateMsg.append("from last ").append(hours).append(" hours. ");
                                    updateMsg.append(results.size()).append(" matches (searching...)\n");
                                    for (int i = 0; i < results.size(); i++) {
                                        updateMsg.append(results.get(i)).append(" ");
                                        String messageLink = "https://matrix.to/#/" + exportRoomId + "/"
                                                + eventIds.get(i);
                                        updateMsg.append(messageLink).append("\n");
                                    }
                                    // Always use original event ID for updates
                                    matrixClient.updateTextMessage(responseRoomId, originalEventId,
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
                    System.out.println("Error during search: " + e.getMessage());
                    break;
                }
            }

            if (results.isEmpty()) {
                if (originalEventId != null) {
                    matrixClient.updateTextMessage(responseRoomId, originalEventId,
                            "No messages found containing all terms: \"" + query + "\" in " + timeInfo + " of "
                                    + exportRoomId + ".");
                } else {
                    matrixClient.sendText(responseRoomId, "No messages found containing all terms: \"" + query
                            + "\" in " + timeInfo + " of " + exportRoomId + ".");
                }
                runningOperations.remove(sender);
                return;
            }

            // Final update with complete results
            StringBuilder response = new StringBuilder();
            response.append("Search results for \"").append(query).append("\" - ");
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
            System.out.println("Failed to perform search: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Error performing search: " + e.getMessage());
            runningOperations.remove(sender);
        }
    }

    private ZoneId getZoneIdFromAbbr(String timezoneAbbr) {
        switch (timezoneAbbr.toUpperCase()) {
            case "PST":
                return ZoneId.of("America/Los_Angeles");
            case "PDT":
                return ZoneId.of("America/Los_Angeles");
            case "EST":
                return ZoneId.of("America/New_York");
            case "EDT":
                return ZoneId.of("America/New_York");
            case "CST":
                return ZoneId.of("America/Chicago");
            case "CDT":
                return ZoneId.of("America/Chicago");
            case "MST":
                return ZoneId.of("America/Denver");
            case "MDT":
                return ZoneId.of("America/Denver");
            case "GMT":
                return ZoneId.of("GMT");
            case "UTC":
                return ZoneId.of("UTC");
            case "CET":
                return ZoneId.of("Europe/Paris");
            case "CEST":
                return ZoneId.of("Europe/Paris");
            case "JST":
                return ZoneId.of("Asia/Tokyo");
            default:
                return ZoneId.of("UTC");
        }
    }
}
