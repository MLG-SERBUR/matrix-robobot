package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the !autosummary command: summarizes unread messages using Arli AI
 * Requires >100 unread messages that are >6 hours old
 */
public class AutoSummaryService {

    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, String> lastReadEventId = new ConcurrentHashMap<>();

    private final Set<String> enabledUsers = ConcurrentHashMap.newKeySet();
    private final MatrixClient matrixClient;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserver;
    private final String accessToken;
    private final String arliApiKey;
    private final Path persistenceFile;

    public static class UnreadMessagesResult {
        public List<String> logs;
        public long oldestTimestampMs;

        public UnreadMessagesResult(List<String> logs, long oldestTimestampMs) {
            this.logs = logs;
            this.oldestTimestampMs = oldestTimestampMs;
        }
    }

    public AutoSummaryService(MatrixClient matrixClient, HttpClient httpClient, ObjectMapper mapper,
                              String homeserver, String accessToken, String arliApiKey) {
        this.matrixClient = matrixClient;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.arliApiKey = arliApiKey;
        this.persistenceFile = Paths.get("autosummary_enabled_users.json");

        // Load persisted enabled users
        loadEnabledUsers();
    }

    /**
     * Toggle autosummary feature for a user
     */
    public void toggleAutoSummary(String userId, String roomId) {
        if (enabledUsers.contains(userId)) {
            enabledUsers.remove(userId);
            matrixClient.sendText(roomId, "Auto-summary disabled.");
        } else {
            enabledUsers.add(userId);
            matrixClient.sendText(roomId, "Auto-summary enabled. I will DM you with a summary when you have >100 unread messages >6 hours old.");
        }
        saveEnabledUsers();
    }

    /**
     * Execute the !autosummary command
     */
    public void executeAutoSummary(String sender, String responseRoomId, String exportRoomId) {
        try {
            // Get user's last read receipt in the export room
            String lastReadEventId = getReadReceipt(exportRoomId, sender);

            if (lastReadEventId == null) {
                matrixClient.sendText(responseRoomId, "No read receipt found for you in the export room.");
                return;
            }

            // Fetch unread messages after the last read event
            UnreadMessagesResult unreadMessages = fetchUnreadMessages(exportRoomId, lastReadEventId);

            if (unreadMessages.logs.isEmpty()) {
                matrixClient.sendText(responseRoomId, "No unread messages found.");
                return;
            }

            // Check thresholds: > 100 messages and > 6 hours old
            if (unreadMessages.logs.size() <= 100) {
                matrixClient.sendText(responseRoomId, "Not enough unread messages (" + unreadMessages.logs.size() + "/100+) for summary.");
                return;
            }

            long oldestMessageTime = unreadMessages.oldestTimestampMs;
            long now = System.currentTimeMillis();
            long ageHours = (now - oldestMessageTime) / (1000 * 60 * 60);

            if (ageHours < 6) {
                matrixClient.sendText(responseRoomId, "Unread messages too recent (" + ageHours + "/6+ hours). Need older messages.");
                return;
            }

            // All thresholds met, query Arli AI with the unread messages
            queryArliAISummary(unreadMessages.logs, responseRoomId);

        } catch (Exception e) {
            System.out.println("Auto-summary failed: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Auto-summary failed: " + e.getMessage());
        }
    }

    /**
     * Get user's last read receipt in the room
     */
    private String getReadReceipt(String roomId, String userId) {
        try {
            // We need to get messages and check read receipts
            String messagesUrl = homeserver + "/_matrix/client/v3/rooms/" +
                URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/messages?dir=b&limit=100";

            HttpRequest msgReq = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());

            if (msgResp.statusCode() != 200) {
                return null;
            }

            JsonNode root = mapper.readTree(msgResp.body());

            // Look for read receipt in messages by querying the latest message and working backwards
            String readReceipt = null;
            JsonNode chunk = root.path("chunk");

            for (JsonNode event : chunk) {
                String eventId = event.path("event_id").asText(null);
                String type = event.path("type").asText(null);

                if ("m.room.message".equals(type) && eventId != null) {
                    // This is a reasonable estimate - the last message seen
                    readReceipt = eventId;
                    break;
                }
            }

            return readReceipt;
        } catch (Exception e) {
            System.out.println("Error getting read receipt: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetch unread messages after the last read event
     */
    private UnreadMessagesResult fetchUnreadMessages(String roomId, String lastReadEventId) {
        List<String> unreadMessages = new ArrayList<>();
        long oldestTimestamp = System.currentTimeMillis();

        try {
            String messagesUrl = homeserver + "/_matrix/client/v3/rooms/" +
                URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/messages?dir=b&limit=1000";

            HttpRequest msgReq = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());

            if (msgResp.statusCode() != 200) {
                return new UnreadMessagesResult(unreadMessages, oldestTimestamp);
            }

            JsonNode root = mapper.readTree(msgResp.body());
            JsonNode chunk = root.path("chunk");

            for (JsonNode event : chunk) {
                String eventId = event.path("event_id").asText(null);
                String type = event.path("type").asText(null);
                long ts = event.path("origin_server_ts").asLong(System.currentTimeMillis());

                if (eventId != null && eventId.equals(lastReadEventId)) {
                    break;
                }

                if (!"m.room.message".equals(type)) continue;

                String body = event.path("content").path("body").asText(null);
                String sender = event.path("sender").asText(null);

                if (body != null && sender != null) {
                    unreadMessages.add("<" + sender + "> " + body);
                    oldestTimestamp = Math.min(oldestTimestamp, ts);
                }
            }

        } catch (Exception e) {
            System.out.println("Error fetching unread messages: " + e.getMessage());
        }

        return new UnreadMessagesResult(unreadMessages, oldestTimestamp);
    }

    /**
     * Query Arli AI to summarize the messages
     */
    private void queryArliAISummary(List<String> messages, String responseRoomId) {
        try {
            // Build context for Arli AI
            StringBuilder context = new StringBuilder();
            for (String msg : messages) {
                context.append(msg).append("\n");
            }

            // Call Arli AI API with summarization prompt
            if (arliApiKey == null || arliApiKey.isEmpty()) {
                matrixClient.sendText(responseRoomId, "Arli AI API key not configured.");
                return;
            }

            String prompt = "Please provide a concise summary of the following chat messages in 2-3 paragraphs:\n\n" + context.toString();

            // Construct Arli AI API request
            String arliUrl = "https://api.arliai.com/v1/chat/completions";
            String requestBody = "{" +
                "\"model\": \"arliai/v1\"," +
                "\"messages\": [{" +
                "\"role\": \"user\"," +
                "\"content\": " + escapeJson(prompt) +
                "}]," +
                "\"max_tokens\": 1000" +
                "}";

            HttpRequest arliReq = HttpRequest.newBuilder()
                    .uri(URI.create(arliUrl))
                    .header("Authorization", "Bearer " + arliApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> arliResp = httpClient.send(arliReq, HttpResponse.BodyHandlers.ofString());

            if (arliResp.statusCode() == 200) {
                JsonNode responseJson = mapper.readTree(arliResp.body());
                String summary = responseJson.path("choices").path(0).path("message").path("content").asText(null);

                if (summary != null && !summary.isEmpty()) {
                    matrixClient.sendMarkdown(responseRoomId, "**Summary of " + messages.size() + " unread messages:**\n\n" + summary);
                } else {
                    matrixClient.sendText(responseRoomId, "Arli AI returned an empty response.");
                }
            } else {
                System.out.println("Arli AI API error: " + arliResp.statusCode() + " - " + arliResp.body());
                matrixClient.sendText(responseRoomId, "Arli AI API error: " + arliResp.statusCode());
            }

        } catch (Exception e) {
            System.out.println("Arli AI query failed: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Arli AI query failed: " + e.getMessage());
        }
    }

    /**
     * Escape JSON string values
     */
    private String escapeJson(String input) {
        return "\"" + input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    /**
     * Load enabled users from persistence file
     */
    private void loadEnabledUsers() {
        if (!Files.exists(persistenceFile)) {
            return;
        }

        try {
            String content = Files.readString(persistenceFile);
            String[] users = mapper.readValue(content, String[].class);
            enabledUsers.addAll(Arrays.asList(users));
            System.out.println("Loaded " + users.length + " autosummary enabled users from persistence");
        } catch (IOException e) {
            System.err.println("Error loading autosummary enabled users: " + e.getMessage());
        }
    }

    /**
     * Processes ephemeral events (read receipts) from the sync loop.
     */
    public void processEphemeralEvents(String roomId, JsonNode ephemeralEvents, String exportRoomId) {
        // Only run logic if this is the export room
        if (!roomId.equals(exportRoomId)) return;
        
        if (ephemeralEvents == null || !ephemeralEvents.isArray()) return;

        for (JsonNode event : ephemeralEvents) {
            if ("m.receipt".equals(event.path("type").asText())) {
                processReceiptEvent(roomId, event.path("content"));
            }
        }
    }

    private void processReceiptEvent(String roomId, JsonNode content) {
        // Content structure: { "$event_id": { "m.read": { "@user_id": { "ts": 1234 } } } }
        Iterator<String> eventIds = content.fieldNames();
        
        while (eventIds.hasNext()) {
            String eventId = eventIds.next();
            JsonNode readNode = content.path(eventId).path("m.read");
            
            Iterator<String> userIds = readNode.fieldNames();
            while (userIds.hasNext()) {
                String userId = userIds.next();
                
                // 1. Check if user has automatic summarization enabled
                if (!enabledUsers.contains(userId)) continue;

                long now = System.currentTimeMillis();
                long lastTrigger = lastTriggerTime.getOrDefault(userId, 0L);

                // 2. Check debounce (6 hours) - prevents spamming the user
                // We use the trigger time to ensure we don't spam if they read messages in a row.
                if (now - lastTrigger < 21600000) { // 6 hours in milliseconds
                    // Update the last known read event, but don't trigger
                    lastReadEventId.put(userId, eventId);
                    continue;
                }

                // 3. Check for at least 100 unread messages that are at least 6 hours old
                String previousEventId = lastReadEventId.get(userId);
                
                // If we have a previous read state, check the gap
                if (previousEventId != null && !previousEventId.equals(eventId)) {
                    UnreadMessagesResult unreadMessages = fetchUnreadMessages(roomId, previousEventId);
                    
                    if (unreadMessages.logs.size() > 100) {
                        long oldestMessageTime = unreadMessages.oldestTimestampMs;
                        long ageHours = (now - oldestMessageTime) / (1000 * 60 * 60);
                        
                        if (ageHours >= 6) {
                            triggerSummary(roomId, userId, unreadMessages.logs);
                            lastTriggerTime.put(userId, now);
                        }
                    }
                }

                // Update state for next time
                lastReadEventId.put(userId, eventId);
            }
        }
    }

    private void triggerSummary(String exportRoomId, String userId, List<String> messages) {
        // Find a DM room with the user
        String dmRoomId = findDirectMessageRoom(userId);
        if (dmRoomId != null) {
            System.out.println("Triggering Auto-Summary for " + userId);
            // We run this in a separate thread to not block the sync loop
            new Thread(() -> {
                try {
                    // Call Arli AI to generate summary
                    if (arliApiKey == null || arliApiKey.isEmpty()) {
                        matrixClient.sendText(dmRoomId, "Arli AI API key not configured.");
                        return;
                    }
                    
                    StringBuilder context = new StringBuilder();
                    for (String msg : messages) {
                        context.append(msg).append("\n");
                    }
                    
                    String prompt = "Please provide a concise summary of the following chat messages in 2-3 paragraphs:\n\n" + context.toString();
                    
                    String arliUrl = "https://api.arliai.com/v1/chat/completions";
                    String requestBody = "{" +
                        "\"model\": \"arliai/v1\"," +
                        "\"messages\": [{" +
                        "\"role\": \"user\"," +
                        "\"content\": " + escapeJson(prompt) +
                        "}]," +
                        "\"max_tokens\": 1000" +
                        "}";
                    
                    HttpRequest arliReq = HttpRequest.newBuilder()
                        .uri(URI.create(arliUrl))
                        .header("Authorization", "Bearer " + arliApiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                    
                    HttpResponse<String> arliResp = httpClient.send(arliReq, HttpResponse.BodyHandlers.ofString());
                    
                    if (arliResp.statusCode() == 200) {
                        JsonNode responseJson = mapper.readTree(arliResp.body());
                        String summary = responseJson.path("choices").path(0).path("message").path("content").asText(null);
                        
                        if (summary != null && !summary.isEmpty()) {
                            matrixClient.sendMarkdown(dmRoomId, "**Automatic summary of " + messages.size() + " unread messages:**\n\n" + summary);
                        } else {
                            matrixClient.sendText(dmRoomId, "Arli AI returned an empty response.");
                        }
                    } else {
                        System.out.println("Arli AI API error: " + arliResp.statusCode() + " - " + arliResp.body());
                        matrixClient.sendText(dmRoomId, "Arli AI API error: " + arliResp.statusCode());
                    }
                    
                } catch (Exception e) {
                    System.out.println("Arli AI query failed: " + e.getMessage());
                    matrixClient.sendText(dmRoomId, "Arli AI query failed: " + e.getMessage());
                }
            }).start();
        } else {
            System.out.println("Could not find DM room for auto-summary user: " + userId);
        }
    }

    /**
     * Helper to find a Room ID that is a DM with the specific user.
     */
    private String findDirectMessageRoom(String targetUserId) {
        try {
            String url = homeserver + "/_matrix/client/v3/joined_rooms";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            // FIX: Use local httpClient instead of matrixClient.getClient()
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode joined = mapper.readTree(resp.body()).path("joined_rooms");
            
            if (joined.isArray()) {
                for (JsonNode roomNode : joined) {
                    String rId = roomNode.asText();
                    if (isDmWithUser(rId, targetUserId)) {
                        return rId;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean isDmWithUser(String roomId, String targetUserId) {
        try {
            // Check members
            String url = homeserver + "/_matrix/client/v3/rooms/" + roomId + "/joined_members";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            // FIX: Use local httpClient instead of matrixClient.getClient()
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode members = mapper.readTree(resp.body()).path("joined");
            
            // A DM usually has exactly 2 members: the bot and the target user
            if (members.size() == 2 && members.has(targetUserId)) {
                return true;
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * Save enabled users to persistence file
     */
    private void saveEnabledUsers() {
        try {
            String[] users = enabledUsers.toArray(new String[0]);
            String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(users);
            Files.writeString(persistenceFile, content);
        } catch (IOException e) {
            System.err.println("Error saving autosummary enabled users: " + e.getMessage());
        }
    }

    /**
     * Get whether a user has autosummary enabled
     */
    public boolean isEnabled(String userId) {
        return enabledUsers.contains(userId);
    }
}
