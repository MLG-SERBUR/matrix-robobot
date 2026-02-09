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
    private final Set<String> enabledSummaryUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSummaryTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, RoomHistoryManager.EventInfo> lastReadInfo = new ConcurrentHashMap<>();

    private final MatrixClient matrixClient;
    private final LastMessageService lastMessageService;
    private final AIService aiService;
    private final TimezoneService timezoneService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserver;
    private final String accessToken;
    private final Path persistenceFile;
    private final Path summaryPersistenceFile;

    public AutoLastService(MatrixClient matrixClient, LastMessageService lastMessageService,
            AIService aiService, TimezoneService timezoneService,
            HttpClient httpClient, ObjectMapper mapper, String homeserver, String accessToken) {
        this.matrixClient = matrixClient;
        this.lastMessageService = lastMessageService;
        this.aiService = aiService;
        this.timezoneService = timezoneService;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.persistenceFile = Paths.get("autolast_enabled_users.json");
        this.summaryPersistenceFile = Paths.get("autosummary_enabled_users.json");

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
            matrixClient.sendText(roomId,
                    "Auto-!last enabled. I will DM you your last message when you read the export room after being away.");
        }
        saveEnabledUsers();
    }

    /**
     * Toggles the summary feature for a user.
     */
    public void toggleAutoSummary(String userId, String roomId) {
        if (enabledSummaryUsers.contains(userId)) {
            enabledSummaryUsers.remove(userId);
            matrixClient.sendText(roomId, "Auto-!summary disabled.");
        } else {
            enabledSummaryUsers.add(userId);
            matrixClient.sendText(roomId,
                    "Auto-!summary enabled. I will DM you an AI summary when you read the export room after being away for over an hour with over 100 unread messages.");
        }
        saveEnabledUsers();
    }

    /**
     * Processes ephemeral events (read receipts) from the sync loop.
     */
    public void processEphemeralEvents(String roomId, JsonNode ephemeralEvents, String exportRoomId) {
        // Only run logic if this is the export room
        if (!roomId.equals(exportRoomId))
            return;

        if (ephemeralEvents == null || !ephemeralEvents.isArray())
            return;

        for (JsonNode event : ephemeralEvents) {
            if ("m.receipt".equals(event.path("type").asText())) {
                processReceiptEvent(roomId, event.path("content"));
            }
        }
    }

    private void processReceiptEvent(String roomId, JsonNode content) {
        // Content structure: { "$event_id": { "m.read": { "@user_id": { "ts": 1234 } }
        // } }
        Iterator<String> eventIds = content.fieldNames();

        while (eventIds.hasNext()) {
            String eventId = eventIds.next();
            JsonNode readNode = content.path(eventId).path("m.read");

            Iterator<String> userIds = readNode.fieldNames();
            while (userIds.hasNext()) {
                String userId = userIds.next();

                // 1. Check if user is enabled for at least one feature
                boolean lastEnabled = enabledUsers.contains(userId);
                boolean summaryEnabled = enabledSummaryUsers.contains(userId);
                
                if (!lastEnabled && !summaryEnabled)
                    continue;

                long now = System.currentTimeMillis();
                long ts = readNode.path(userId).path("ts").asLong(0);
                RoomHistoryManager.EventInfo currentReadInfo = new RoomHistoryManager.EventInfo(eventId, ts);
                RoomHistoryManager.EventInfo previousReadInfo = lastReadInfo.get(userId);

                // Update state for next trigger check, but we need the previous info to check gaps
                if (previousReadInfo == null || previousReadInfo.eventId.equals(eventId)) {
                    lastReadInfo.put(userId, currentReadInfo);
                    continue;
                }

                // 2. Handle Auto-Last
                if (lastEnabled) {
                    long lastTrigger = lastTriggerTime.getOrDefault(userId, 0L);
                    // Debounce (30 minutes)
                    if (now - lastTrigger >= 1800000) {
                        if (hasAtLeastMessages(roomId, previousReadInfo.eventId, eventId, 30)) {
                            triggerLastMessage(roomId, userId, previousReadInfo);
                            lastTriggerTime.put(userId, now);
                        }
                    }
                }

                // 3. Handle Auto-Summary
                if (summaryEnabled) {
                    long lastSummaryTrigger = lastSummaryTriggerTime.getOrDefault(userId, 0L);
                    // Threshold: > 1 hour gap
                    if (now - lastSummaryTrigger >= 3600000) {
                        if (hasAtLeastMessages(roomId, previousReadInfo.eventId, eventId, 100)) {
                            triggerSummary(roomId, userId);
                            lastSummaryTriggerTime.put(userId, now);
                        }
                    }
                }

                // Update state for next time
                lastReadInfo.put(userId, currentReadInfo);
            }
        }
    }

    private void triggerLastMessage(String exportRoomId, String userId, RoomHistoryManager.EventInfo previousReadInfo) {
        // Find a DM room with the user
        String dmRoomId = findDirectMessageRoom(userId);
        if (dmRoomId != null) {
            System.out.println("Triggering Auto-Last for " + userId);
            // We run this in a separate thread to not block the sync loop
            new Thread(() -> lastMessageService.sendLastMessageAndReadReceipt(exportRoomId, userId, dmRoomId,
                    previousReadInfo)).start();
        } else {
            System.out.println("Could not find DM room for auto-last user: " + userId);
        }
    }

    private void triggerSummary(String exportRoomId, String userId) {
        String dmRoomId = findDirectMessageRoom(userId);
        if (dmRoomId != null) {
            System.out.println("Triggering Auto-Summary for " + userId);
            java.time.ZoneId zoneId = timezoneService.getZoneIdForUser(userId);
            if (zoneId == null) {
                System.out.println("No timezone set for " + userId + ", skipping auto-summary trigger.");
                return;
            }

            new Thread(() -> {
                try {
                    aiService.queryAIUnread(dmRoomId, exportRoomId, userId, zoneId, null,
                            AIService.Prompts.OVERVIEW_PREFIX, new java.util.concurrent.atomic.AtomicBoolean(false));
                } catch (Exception e) {
                    System.err.println("Error running auto-summary: " + e.getMessage());
                }
            }).start();
        } else {
            System.out.println("Could not find DM room for auto-summary user: " + userId);
        }
    }

    /**
     * Checks if there are >= threshold messages between fromEventId and toEventId
     * (exclusive of from, inclusive of to).
     */
    private boolean hasAtLeastMessages(String roomId, String previousReadId, String currentReadId, int threshold) {
        try {
            // Fetch recent messages
            String url = homeserver + "/_matrix/client/v3/rooms/" + roomId + "/messages?dir=b&limit=100";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            // FIX: Use local httpClient instead of matrixClient.getClient()
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                return false;

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

            return messagesFound >= threshold;

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
        if (Files.exists(persistenceFile)) {
            try {
                String content = Files.readString(persistenceFile);
                String[] users = mapper.readValue(content, String[].class);
                enabledUsers.addAll(Arrays.asList(users));
                System.out.println("Loaded " + users.length + " autolast enabled users from persistence");
            } catch (IOException e) {
                System.err.println("Error loading autolast enabled users: " + e.getMessage());
            }
        }

        if (Files.exists(summaryPersistenceFile)) {
            try {
                String content = Files.readString(summaryPersistenceFile);
                String[] users = mapper.readValue(content, String[].class);
                enabledSummaryUsers.addAll(Arrays.asList(users));
                System.out.println("Loaded " + users.length + " autosummary enabled users from persistence");
            } catch (IOException e) {
                System.err.println("Error loading autosummary enabled users: " + e.getMessage());
            }
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

            String[] summaryUsers = enabledSummaryUsers.toArray(new String[0]);
            String summaryContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summaryUsers);
            Files.writeString(summaryPersistenceFile, summaryContent);
        } catch (IOException e) {
            System.err.println("Error saving enabled users: " + e.getMessage());
        }
    }
}