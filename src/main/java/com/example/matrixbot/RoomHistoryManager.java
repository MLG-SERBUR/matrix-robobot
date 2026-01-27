package com.example.matrixbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages fetching and processing room chat history from the Matrix server.
 */
public class RoomHistoryManager {
    
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;

    public static class ChatLogsResult {
        public List<String> logs;
        public String firstEventId;
        
        public ChatLogsResult(List<String> logs, String firstEventId) {
            this.logs = logs;
            this.firstEventId = firstEventId;
        }
    }

    public static class ChatLogsWithIds {
        public List<String> logs;
        public List<String> eventIds;
        
        public ChatLogsWithIds(List<String> logs, List<String> eventIds) {
            this.logs = logs;
            this.eventIds = eventIds;
        }
    }

    public RoomHistoryManager(HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, String accessToken) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
    }

    /**
     * Fetch room history as simple log strings
     */
    public List<String> fetchRoomHistory(String roomId, int hours, String fromToken) {
        ChatLogsResult result = fetchRoomHistoryDetailed(roomId, hours, fromToken, -1, -1, ZoneId.of("America/Los_Angeles"));
        return result.logs;
    }

    /**
     * Fetch room history with event IDs
     */
    public ChatLogsWithIds fetchRoomHistoryWithIds(String roomId, int hours, String fromToken, long startTimestamp, long endTime, ZoneId zoneId) {
        List<String> logs = new ArrayList<>();
        List<String> eventIds = new ArrayList<>();
        
        long startTime = (startTimestamp > 0) ? startTimestamp : System.currentTimeMillis() - (long) hours * 3600L * 1000L;
        long calculatedEndTime = (endTime > 0) ? endTime : System.currentTimeMillis();
        
        String token = getPaginationToken(roomId, fromToken);
        
        while (token != null) {
            try {
                String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                HttpRequest msgReq = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
                if (msgResp.statusCode() != 200) {
                    System.out.println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                    break;
                }
                JsonNode root = mapper.readTree(msgResp.body());
                JsonNode chunk = root.path("chunk");
                if (!chunk.isArray() || chunk.size() == 0) break;

                boolean reachedStart = false;
                for (JsonNode ev : chunk) {
                    if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
                    long originServerTs = ev.path("origin_server_ts").asLong(0);
                    
                    if (originServerTs > calculatedEndTime) {
                        continue;
                    }

                    if (originServerTs < startTime) {
                        reachedStart = true;
                        break;
                    }
                    
                    String body = ev.path("content").path("body").asText(null);
                    String sender = ev.path("sender").asText(null);
                    String eventId = ev.path("event_id").asText(null);
                    if (body != null && sender != null && eventId != null) {
                        String timestamp = Instant.ofEpochMilli(originServerTs)
                                .atZone(zoneId)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                        logs.add("[" + timestamp + "] <" + sender + "> " + body);
                        eventIds.add(eventId);
                    }
                }
                
                if (reachedStart) {
                    break;
                }
                
                token = root.path("end").asText(null);

            } catch (Exception e) {
                System.out.println("Error fetching room history: " + e.getMessage());
                break;
            }
        }
        Collections.reverse(logs);
        Collections.reverse(eventIds);
        return new ChatLogsWithIds(logs, eventIds);
    }

    /**
     * Fetch room history with first event ID tracking
     */
    public ChatLogsResult fetchRoomHistoryDetailed(String roomId, int hours, String fromToken, long startTimestamp, long endTime, ZoneId zoneId) {
        List<String> lines = new ArrayList<>();
        String firstEventId = null;
        
        long startTime = (startTimestamp > 0) ? startTimestamp : System.currentTimeMillis() - (long) hours * 3600L * 1000L;
        long calculatedEndTime = (endTime > 0) ? endTime : System.currentTimeMillis();
        
        String token = getPaginationToken(roomId, fromToken);
        
        while (token != null) {
            try {
                String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                HttpRequest msgReq = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
                if (msgResp.statusCode() != 200) {
                    System.out.println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                    break;
                }
                JsonNode root = mapper.readTree(msgResp.body());
                JsonNode chunk = root.path("chunk");
                if (!chunk.isArray() || chunk.size() == 0) break;

                boolean reachedStart = false;
                for (JsonNode ev : chunk) {
                    if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
                    long originServerTs = ev.path("origin_server_ts").asLong(0);
                    
                    if (originServerTs > calculatedEndTime) {
                        continue;
                    }
                    if (originServerTs < startTime) {
                        reachedStart = true;
                        break;
                    }
                    
                    String body = ev.path("content").path("body").asText(null);
                    String sender = ev.path("sender").asText(null);
                    String eventId = ev.path("event_id").asText(null);
                    if (body != null && sender != null) {
                        String timestamp = Instant.ofEpochMilli(originServerTs)
                                .atZone(zoneId)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                        lines.add("[" + timestamp + "] <" + sender + "> " + body);
                        
                        firstEventId = eventId;
                    }
                }
                
                if (reachedStart) {
                    break;
                }
                
                token = root.path("end").asText(null);

            } catch (Exception e) {
                System.out.println("Error fetching room history: " + e.getMessage());
                break;
            }
        }
        Collections.reverse(lines);
        return new ChatLogsResult(lines, firstEventId);
    }

    /**
     * Get the last message sent by a user in a room
     */
    public String getLastMessageFromSender(String roomId, String sender) {
        try {
            String token = getPaginationToken(roomId, null);
            if (token == null) {
                return null;
            }
            
            String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                    + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
            HttpRequest msgReq = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
            
            if (msgResp.statusCode() != 200) {
                System.out.println("Failed to fetch messages for last message: " + msgResp.statusCode());
                return null;
            }
            
            JsonNode msgRoot = mapper.readTree(msgResp.body());
            JsonNode chunk = msgRoot.path("chunk");
            if (!chunk.isArray()) {
                return null;
            }
            
            for (JsonNode ev : chunk) {
                if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
                String msgSender = ev.path("sender").asText(null);
                if (sender.equals(msgSender)) {
                    return ev.path("event_id").asText(null);
                }
            }
            
            // Try next page if not found
            String endToken = msgRoot.path("end").asText(null);
            if (endToken != null) {
                String messagesUrl2 = homeserverUrl + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(endToken, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                HttpRequest msgReq2 = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl2))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> msgResp2 = httpClient.send(msgReq2, HttpResponse.BodyHandlers.ofString());
                
                if (msgResp2.statusCode() == 200) {
                    JsonNode msgRoot2 = mapper.readTree(msgResp2.body());
                    JsonNode chunk2 = msgRoot2.path("chunk");
                    if (chunk2.isArray()) {
                        for (JsonNode ev : chunk2) {
                            if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
                            String msgSender = ev.path("sender").asText(null);
                            if (sender.equals(msgSender)) {
                                return ev.path("event_id").asText(null);
                            }
                        }
                    }
                }
            }
            
            return null;
            
        } catch (Exception e) {
            System.out.println("Error getting last message from sender: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get pagination token from sync response
     */
    private String getPaginationToken(String roomId, String providedToken) {
        if (providedToken != null) {
            return providedToken;
        }
        
        try {
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(homeserverUrl + "/_matrix/client/v3/sync?timeout=0"))
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
        } catch (Exception ignore) {
            // ignore
        }
        return null;
    }
}
