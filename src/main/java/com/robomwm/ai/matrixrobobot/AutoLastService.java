package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoLastService {

    private final Set<String> enabledUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, String> lastReadEventId = new ConcurrentHashMap<>();
    
    private final MatrixClient matrixClient;
    private final LastMessageService lastMessageService;
    private final HttpClient httpClient; // Added HttpClient
    private final ObjectMapper mapper;
    private final String homeserver;
    private final String accessToken;
    private final Path persistenceFile;

    public AutoLastService(MatrixClient matrixClient, LastMessageService lastMessageService, 
                           HttpClient httpClient, ObjectMapper mapper, String homeserver, String accessToken) {
        this.matrixClient = matrixClient;
        this.lastMessageService = lastMessageService;
        this.httpClient = httpClient; // Store HttpClient
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.persistenceFile = Paths.get("autolast_enabled_users.json");
        
        // Load persisted enabled users
        loadEnabledUsers();
    }

    /**
     * Toggles the feature for a user.
     */
    public void toggleAutoLast(String userId, String roomId) {
        if (enabledUsers.contains(userId)) {
            enabledUsers.remove(userId);
            matrixClient.sendText(roomId, "Auto-!last disabled.");
        } else {
            enabledUsers.add(userId);
            matrixClient.sendText(roomId, "Auto-!last enabled. I will DM you a summary when you read the export room after being away.");
        }
        saveEnabledUsers();
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
                
                // 1. Check if user is enabled
                if (!enabledUsers.contains(userId)) continue;

                long now = System.currentTimeMillis();
                long lastTrigger = lastTriggerTime.getOrDefault(userId, 0L);

                // 2. Check debounce (1 minute) - "last read was more than a minute ago"
                // We use the trigger time to ensure we don't spam if they read 5 messages in a row.
                if (now - lastTrigger < 60000) {
                    // Update the last known read event, but don't trigger
                    lastReadEventId.put(userId, eventId);
                    continue;
                }

                // 3. Check for at least 2 unread messages
                String previousEventId = lastReadEventId.get(userId);
                
                // If we have a previous read state, check the gap
                if (previousEventId != null && !previousEventId.equals(eventId)) {
                    if (hasAtLeastTwoMessages(roomId, previousEventId, eventId)) {
                        triggerLastMessage(roomId, userId);
                        lastTriggerTime.put(userId, now);
                    }
                }

                // Update state for next time
                lastReadEventId.put(userId, eventId);
            }
        }
    }

    private void triggerLastMessage(String exportRoomId, String userId) {
        // Find a DM room with the user
        String dmRoomId = findDirectMessageRoom(userId);
        if (dmRoomId != null) {
            System.out.println("Triggering Auto-Last for " + userId);
            // Get the cached previous read event ID before updating it
            String previousReadEventId = lastReadEventId.get(userId);
            // We run this in a separate thread to not block the sync loop
            new Thread(() -> lastMessageService.sendLastMessageAndReadReceipt(exportRoomId, userId, dmRoomId, previousReadEventId)).start();
        } else {
            System.out.println("Could not find DM room for auto-last user: " + userId);
        }
    }

    /**
     * Checks if there are >= 2 messages between fromEventId and toEventId (exclusive of from, inclusive of to).
     */
    private boolean hasAtLeastTwoMessages(String roomId, String previousReadId, String currentReadId) {
        try {
            // Fetch recent messages
            String url = homeserver + "/_matrix/client/v3/rooms/" + roomId + "/messages?dir=b&limit=20";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            
            // FIX: Use local httpClient instead of matrixClient.getClient()
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;

            JsonNode root = mapper.readTree(resp.body());
            JsonNode chunk = root.path("chunk");
            
            int messagesFound = 0;
            boolean foundCurrent = false;

            for (JsonNode event : chunk) {
                String id = event.path("event_id").asText();
                String type = event.path("type").asText();
                
                // Only count actual messages
                boolean isMessage = "m.room.message".equals(type);

                if (id.equals(currentReadId)) {
                    foundCurrent = true;
                }

                if (foundCurrent && isMessage) {
                    messagesFound++;
                }

                if (id.equals(previousReadId)) {
                    break;
                }
            }
            
            return messagesFound >= 2;

        } catch (Exception e) {
            System.err.println("Error checking message gap: " + e.getMessage());
            return false;
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
     * Load enabled users from persistence file.
     */
    private void loadEnabledUsers() {
        if (!Files.exists(persistenceFile)) {
            return;
        }
        
        try {
            String content = Files.readString(persistenceFile);
            String[] users = mapper.readValue(content, String[].class);
            enabledUsers.addAll(Arrays.asList(users));
            System.out.println("Loaded " + users.length + " autolast enabled users from persistence");
        } catch (IOException e) {
            System.err.println("Error loading autolast enabled users: " + e.getMessage());
        }
    }

    /**
     * Save enabled users to persistence file.
     */
    private void saveEnabledUsers() {
        try {
            String[] users = enabledUsers.toArray(new String[0]);
            String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(users);
            Files.writeString(persistenceFile, content);
        } catch (IOException e) {
            System.err.println("Error saving autolast enabled users: " + e.getMessage());
        }
    }
}