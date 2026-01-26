package com.example.matrixbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Manages automatic !last responses when users read the export room.
 * 
 * This class handles:
 * - Detecting when users read messages in the export room
 * - Finding the user's DM with the bot
 * - Checking if conditions are met (minimum messages, minimum time)
 * - Sending automatic !last responses to the user's DM
 */
public class AutoLastManager {
    
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String url;
    private final String accessToken;
    private final String exportRoomId;
    private final String commandRoomId;
    private final String botUserId;
    
    // Track users who have enabled auto-!last
    // Key: userId, Value: enabled (true/false)
    private static final java.util.Map<String, Boolean> autoLastUsers = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Configuration thresholds
    private static final int MIN_UNREAD_MESSAGES = 75;
    private static final long MIN_TIME_HOURS = 1;
    
    public AutoLastManager(HttpClient client, ObjectMapper mapper, String url, 
                          String accessToken, String exportRoomId, 
                          String commandRoomId, String botUserId) {
        this.client = client;
        this.mapper = mapper;
        this.url = url;
        this.accessToken = accessToken;
        this.exportRoomId = exportRoomId;
        this.commandRoomId = commandRoomId;
        this.botUserId = botUserId;
    }
    
    /**
     * Check for users reading the export room and send auto-!last in their DM.
     * This should be called during the sync loop when processing ephemeral events.
     */
    public void checkAndSendAutoLast(JsonNode syncRoot) {
        try {
            JsonNode ephemeralEvents = syncRoot.path("rooms").path("join").path(exportRoomId).path("ephemeral").path("events");
            if (!ephemeralEvents.isArray()) {
                return;
            }
            
            for (JsonNode ev : ephemeralEvents) {
                if ("m.receipt".equals(ev.path("type").asText(null))) {
                    processReceiptEvent(ev);
                }
            }
        } catch (Exception e) {
            System.out.println("Error checking for auto-!last: " + e.getMessage());
        }
    }
    
    /**
     * Handle the !autolast command.
     * This should be called when a user sends !autolast in a DM.
     * 
     * @param roomId The room where the command was sent
     * @param userId The user who sent the command
     * @return true if the command was handled, false otherwise
     */
    public boolean handleAutoLastCommand(String roomId, String userId) {
        // Only allow in DMs (not in command room or export room)
        if (roomId.equals(commandRoomId) || roomId.equals(exportRoomId)) {
            return false;
        }
        
        // Check if this is a DM with the bot
        if (!isDmWithBot(roomId, userId)) {
            return false;
        }
        
        // Toggle auto-!last for this user
        boolean currentlyEnabled = autoLastUsers.getOrDefault(userId, false);
        boolean newStatus = !currentlyEnabled;
        autoLastUsers.put(userId, newStatus);
        
        String statusMessage = newStatus ? "enabled" : "disabled";
        String response = "**Auto-!last " + statusMessage + "**\n\n" +
            "Status: " + statusMessage + "\n" +
            "Threshold: " + MIN_UNREAD_MESSAGES + " unread messages, " + MIN_TIME_HOURS + " hour(s) since last read\n\n" +
            "When enabled, I'll automatically send you a report when you have significant unread content in the export room.";
        
        sendText(roomId, response);
        System.out.println("Auto-!last " + statusMessage + " for user " + userId);
        
        return true;
    }
    
    /**
     * Check if a room is a DM with the bot and a specific user.
     */
    private boolean isDmWithBot(String roomId, String userId) {
        try {
            String membersUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/members";
            HttpRequest membersReq = HttpRequest.newBuilder()
                    .uri(URI.create(membersUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> membersResp = client.send(membersReq, HttpResponse.BodyHandlers.ofString());
            
            if (membersResp.statusCode() != 200) {
                return false;
            }
            
            JsonNode members = mapper.readTree(membersResp.body()).path("chunk");
            if (!members.isArray()) {
                return false;
            }
            
            int memberCount = 0;
            boolean hasUser = false;
            boolean hasBot = false;
            
            for (JsonNode member : members) {
                String membership = member.path("content").path("membership").asText(null);
                String memberUserId = member.path("state_key").asText(null);
                
                if ("join".equals(membership) || "invite".equals(membership)) {
                    memberCount++;
                    if (memberUserId.equals(userId)) {
                        hasUser = true;
                    }
                    if (memberUserId.equals(botUserId)) {
                        hasBot = true;
                    }
                }
            }
            
            // DM should have exactly 2 members (bot + user)
            return memberCount == 2 && hasUser && hasBot;
        } catch (Exception e) {
            System.out.println("Error checking if room is DM with bot: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Process a receipt event to detect users reading the export room.
     */
    private void processReceiptEvent(JsonNode receiptEvent) {
        JsonNode content = receiptEvent.path("content");
        Iterator<String> eventIds = content.fieldNames();
        
        while (eventIds.hasNext()) {
            String eventId = eventIds.next();
            JsonNode receiptData = content.path(eventId).path("m.read");
            Iterator<String> userIds = receiptData.fieldNames();
            
            while (userIds.hasNext()) {
                String userIdReading = userIds.next();
                
                // Skip the bot itself
                if (botUserId != null && userIdReading.equals(botUserId)) {
                    continue;
                }
                
                // Get the timestamp of the read receipt
                JsonNode timestampNode = receiptData.path(userIdReading);
                long readTimestamp = getTimestamp(timestampNode);
                
                // Only process if we have a valid timestamp
                if (readTimestamp > 0) {
                    processUserRead(userIdReading, readTimestamp);
                }
            }
        }
    }
    
    /**
     * Process a user's read receipt and send auto-!last if conditions are met.
     */
    private void processUserRead(String userIdReading, long readTimestamp) {
        try {
            // Check if user has enabled auto-!last
            if (!autoLastUsers.getOrDefault(userIdReading, false)) {
                return;
            }
            
            // Find the user's DM with the bot
            String userDmRoomId = findUserDmRoom(userIdReading);
            
            if (userDmRoomId == null) {
                // User doesn't have a DM with the bot
                return;
            }
            
            // Get the user's last read message in the export room
            String lastReadEventId = getReadReceipt(userIdReading);
            
            if (lastReadEventId == null) {
                // No read receipt found
                return;
            }
            
            // Get the timestamp of the last read message
            long lastReadTimestamp = getMessageTimestamp(lastReadEventId);
            
            if (lastReadTimestamp <= 0) {
                // Couldn't get message timestamp
                return;
            }
            
            // Calculate time difference
            long timeDiff = readTimestamp - lastReadTimestamp;
            long hoursDiff = timeDiff / (1000 * 60 * 60);
            
            // Get message count difference
            int messageCount = getMessageCountBetween(lastReadEventId);
            
            // Check if conditions are met
            if (messageCount >= MIN_UNREAD_MESSAGES && hoursDiff >= MIN_TIME_HOURS) {
                System.out.println("Auto-sending !last to " + userIdReading + " in DM " + userDmRoomId + 
                    " (unread: " + messageCount + " messages, time: " + hoursDiff + " hours)");
                
                // Execute !last command and send the result to the user
                executeLastCommand(userDmRoomId, userIdReading, messageCount, hoursDiff);
            }
        } catch (Exception e) {
            System.out.println("Error processing user read for " + userIdReading + ": " + e.getMessage());
        }
    }
    
    /**
     * Find the user's DM room with the bot.
     */
    private String findUserDmRoom(String userIdReading) {
        try {
            String joinedRoomsUrl = url + "/_matrix/client/v3/joined_rooms";
            HttpRequest joinedReq = HttpRequest.newBuilder()
                    .uri(URI.create(joinedRoomsUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> joinedResp = client.send(joinedReq, HttpResponse.BodyHandlers.ofString());
            
            if (joinedResp.statusCode() != 200) {
                return null;
            }
            
            JsonNode joinedRooms = mapper.readTree(joinedResp.body()).path("joined_rooms");
            if (!joinedRooms.isArray()) {
                return null;
            }
            
            for (JsonNode roomIdNode : joinedRooms) {
                String roomId = roomIdNode.asText();
                
                // Skip configured rooms
                if (roomId.equals(commandRoomId) || roomId.equals(exportRoomId)) {
                    continue;
                }
                
                // Check if this is a DM with the user
                if (isDmWithUser(roomId, userIdReading)) {
                    return roomId;
                }
            }
            
            return null;
        } catch (Exception e) {
            System.out.println("Error finding DM room for " + userIdReading + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if a room is a DM with a specific user.
     */
    private boolean isDmWithUser(String roomId, String userIdReading) {
        try {
            String membersUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/members";
            HttpRequest membersReq = HttpRequest.newBuilder()
                    .uri(URI.create(membersUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> membersResp = client.send(membersReq, HttpResponse.BodyHandlers.ofString());
            
            if (membersResp.statusCode() != 200) {
                return false;
            }
            
            JsonNode members = mapper.readTree(membersResp.body()).path("chunk");
            if (!members.isArray()) {
                return false;
            }
            
            int memberCount = 0;
            boolean hasUser = false;
            
            for (JsonNode member : members) {
                String membership = member.path("content").path("membership").asText(null);
                String memberUserId = member.path("state_key").asText(null);
                
                if ("join".equals(membership) || "invite".equals(membership)) {
                    memberCount++;
                    if (memberUserId.equals(userIdReading)) {
                        hasUser = true;
                    }
                }
            }
            
            // DM should have exactly 2 members (bot + user)
            return memberCount == 2 && hasUser;
        } catch (Exception e) {
            System.out.println("Error checking if room is DM: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the user's last read message in the export room.
     */
    private String getReadReceipt(String userId) {
        try {
            // Try to get from sync response first
            String syncUrl = url + "/_matrix/client/v3/sync?timeout=0";
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(syncUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
            
            if (syncResp.statusCode() == 200) {
                JsonNode root = mapper.readTree(syncResp.body());
                JsonNode roomNode = root.path("rooms").path("join").path(exportRoomId);
                if (!roomNode.isMissingNode()) {
                    JsonNode ephemeral = roomNode.path("ephemeral").path("events");
                    if (ephemeral.isArray()) {
                        for (JsonNode ev : ephemeral) {
                            if ("m.receipt".equals(ev.path("type").asText(null))) {
                                JsonNode content = ev.path("content");
                                Iterator<String> eventIds = content.fieldNames();
                                while (eventIds.hasNext()) {
                                    String eventId = eventIds.next();
                                    JsonNode receiptData = content.path(eventId).path("m.read");
                                    if (receiptData.has(userId)) {
                                        return eventId;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Check room account data
            String encodedRoom = URLEncoder.encode(exportRoomId, StandardCharsets.UTF_8);
            String encodedUser = URLEncoder.encode(userId, StandardCharsets.UTF_8);
            String accountDataUrl = url + "/_matrix/client/v3/user/" + encodedUser + "/rooms/" + encodedRoom + "/account_data/m.read";
            HttpRequest accountReq = HttpRequest.newBuilder()
                    .uri(URI.create(accountDataUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> accountResp = client.send(accountReq, HttpResponse.BodyHandlers.ofString());
            
            if (accountResp.statusCode() == 200) {
                JsonNode accountData = mapper.readTree(accountResp.body());
                String lastRead = accountData.path("event_id").asText(null);
                if (lastRead != null && !lastRead.isEmpty()) {
                    return lastRead;
                }
            }
            
            return null;
        } catch (Exception e) {
            System.out.println("Error getting read receipt for " + userId + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get the timestamp of a message.
     */
    private long getMessageTimestamp(String eventId) {
        try {
            String eventUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(exportRoomId, StandardCharsets.UTF_8) + 
                "/event/" + URLEncoder.encode(eventId, StandardCharsets.UTF_8);
            HttpRequest eventReq = HttpRequest.newBuilder()
                    .uri(URI.create(eventUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> eventResp = client.send(eventReq, HttpResponse.BodyHandlers.ofString());
            
            if (eventResp.statusCode() == 200) {
                JsonNode event = mapper.readTree(eventResp.body());
                return event.path("origin_server_ts").asLong(0);
            }
            
            return 0;
        } catch (Exception e) {
            System.out.println("Error getting message timestamp for " + eventId + ": " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get the number of messages between last read and current.
     */
    private int getMessageCountBetween(String lastReadEventId) {
        try {
            String messagesUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(exportRoomId, StandardCharsets.UTF_8) + 
                "/messages?from=" + URLEncoder.encode(lastReadEventId, StandardCharsets.UTF_8) + "&dir=f&limit=100";
            HttpRequest messagesReq = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> messagesResp = client.send(messagesReq, HttpResponse.BodyHandlers.ofString());
            
            if (messagesResp.statusCode() != 200) {
                return 0;
            }
            
            JsonNode messages = mapper.readTree(messagesResp.body()).path("chunk");
            if (!messages.isArray()) {
                return 0;
            }
            
            int count = 0;
            for (JsonNode msg : messages) {
                if ("m.room.message".equals(msg.path("type").asText(null))) {
                    count++;
                }
            }
            
            return count;
        } catch (Exception e) {
            System.out.println("Error getting message count: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Execute the !last command and send the result to the user.
     */
    private void executeLastCommand(String roomId, String userId, int unreadCount, long hoursDiff) {
        try {
            // Get the last message sent by the user in the export room
            String lastMessageEventId = getLastMessageFromSender(userId);
            
            // Get the last read message for the user in the export room
            String lastReadEventId = getReadReceipt(userId);
            
            StringBuilder response = new StringBuilder();
            
            // Add header with unread info
            response.append("**Auto-!last Report**\n");
            response.append("Unread: ").append(unreadCount).append(" messages\n");
            response.append("Time since last read: ").append(hoursDiff).append(" hours\n\n");
            
            // Last message sent by user
            if (lastMessageEventId != null) {
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + lastMessageEventId;
                response.append("sent: ");
                response.append(messageLink).append("\n");
            } else {
                response.append("sent: No recently sent.\n");
            }
            
            // Last read message
            if (lastReadEventId != null) {
                // Check if it's the latest message
                boolean isLatest = isLatestMessage(lastReadEventId);
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + lastReadEventId;
                
                if (isLatest) {
                    // User is caught up - show message with link to latest
                    response.append("read: no unread. Latest: ");
                    response.append(messageLink).append("\n");
                } else {
                    // User is behind - show only the link to the message they last read
                    response.append("read: ");
                    response.append(messageLink).append("\n");
                }
            } else {
                response.append("read: No read receipt found.\n");
            }
            
            // Send the response
            sendText(roomId, response.toString());
            
        } catch (Exception e) {
            System.out.println("Error executing !last for " + userId + ": " + e.getMessage());
            sendText(roomId, "Error getting last message info: " + e.getMessage());
        }
    }
    
    /**
     * Get the last message sent by a user in the export room.
     */
    private String getLastMessageFromSender(String sender) {
        try {
            // Get room state to find the timeline
            // We'll fetch recent messages and find the last one from this sender
            String syncUrl = url + "/_matrix/client/v3/sync?timeout=0";
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(syncUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
            
            if (syncResp.statusCode() != 200) {
                System.out.println("Failed to sync for last message: " + syncResp.statusCode());
                return null;
            }
            
            JsonNode root = mapper.readTree(syncResp.body());
            JsonNode roomNode = root.path("rooms").path("join").path(exportRoomId);
            if (roomNode.isMissingNode()) {
                return null;
            }
            
            // Get the prev_batch token to fetch history
            String prevBatch = roomNode.path("timeline").path("prev_batch").asText(null);
            if (prevBatch == null) {
                return null;
            }
            
            // First attempt: Fetch recent messages going backwards
            String messagesUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(exportRoomId, StandardCharsets.UTF_8)
                    + "/messages?from=" + URLEncoder.encode(prevBatch, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
            HttpRequest msgReq = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> msgResp = client.send(msgReq, HttpResponse.BodyHandlers.ofString());
            
            if (msgResp.statusCode() != 200) {
                System.out.println("Failed to fetch messages for last message: " + msgResp.statusCode());
                return null;
            }
            
            JsonNode msgRoot = mapper.readTree(msgResp.body());
            JsonNode chunk = msgRoot.path("chunk");
            if (!chunk.isArray()) {
                return null;
            }
            
            // Find the last message from this sender
            for (JsonNode ev : chunk) {
                if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
                String msgSender = ev.path("sender").asText(null);
                if (sender.equals(msgSender)) {
                    return ev.path("event_id").asText(null);
                }
            }
            
            // Also check current timeline events
            JsonNode timeline = roomNode.path("timeline").path("events");
            if (timeline.isArray()) {
                for (JsonNode ev : timeline) {
                    if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
                    String msgSender = ev.path("sender").asText(null);
                    if (sender.equals(msgSender)) {
                        return ev.path("event_id").asText(null);
                    }
                }
            }
            
            // If no message found, try one more time with the end token from the first fetch
            String endToken = msgRoot.path("end").asText(null);
            if (endToken != null) {
                String messagesUrl2 = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(exportRoomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(endToken, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                HttpRequest msgReq2 = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl2))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> msgResp2 = client.send(msgReq2, HttpResponse.BodyHandlers.ofString());
                
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
     * Check if a message is the latest in the room.
     */
    private boolean isLatestMessage(String eventId) {
        try {
            // Get the latest message in the room
            String syncUrl = url + "/_matrix/client/v3/sync?timeout=0";
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(syncUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
            
            if (syncResp.statusCode() != 200) {
                return false;
            }
            
            JsonNode root = mapper.readTree(syncResp.body());
            JsonNode roomNode = root.path("rooms").path("join").path(exportRoomId);
            if (roomNode.isMissingNode()) {
                return false;
            }
            
            // Check timeline events
            JsonNode timeline = roomNode.path("timeline").path("events");
            if (timeline.isArray() && timeline.size() > 0) {
                // Find the latest message event
                for (int i = timeline.size() - 1; i >= 0; i--) {
                    JsonNode ev = timeline.get(i);
                    if ("m.room.message".equals(ev.path("type").asText(null))) {
                        String latestEventId = ev.path("event_id").asText(null);
                        return eventId.equals(latestEventId);
                    }
                }
            }
            
            return false;
            
        } catch (Exception e) {
            System.out.println("Error checking if message is latest: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Send a text message to a room.
     */
    private void sendText(String roomId, String message) {
        try {
            String txnId = "m" + System.currentTimeMillis();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = url + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + txnId;
            
            String sanitizedMessage = sanitizeUserIds(message);
            
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("msgtype", "m.text");
            payload.put("body", sanitizedMessage);
            payload.put("m.mentions", java.util.Map.of());
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Sent auto-!last to " + roomId + " -> " + resp.statusCode());
        } catch (Exception e) {
            System.out.println("Failed to send auto-!last: " + e.getMessage());
        }
    }
    
    /**
     * Get timestamp from a timestamp node.
     */
    private long getTimestamp(JsonNode timestampNode) {
        if (timestampNode.isObject() && timestampNode.has("ts")) {
            return timestampNode.path("ts").asLong(0);
        }
        return timestampNode.asLong(0);
    }
    
    /**
     * Sanitize user IDs to prevent pings.
     */
    private String sanitizeUserIds(String message) {
        return message.replaceAll("(?<!`)(?<!`<)(@[a-zA-Z0-9._=-]+:[a-zA-Z0-9.-]+)(?!`)", "`$1`");
    }
}
