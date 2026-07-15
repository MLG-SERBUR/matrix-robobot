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
    private final Set<String> enabledTldrUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> userLastPublicPref = new ConcurrentHashMap<>();
    private final Map<String, Boolean> userTldrPublicPref = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTldrTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, RoomHistoryManager.EventInfo> lastReadInfo = new ConcurrentHashMap<>();
    
    // Track if we've processed the first sync to avoid triggering on old receipts
    private volatile boolean firstSyncProcessed = false;

    private final MatrixClient matrixClient;
    private final LastMessageService lastMessageService;
    private final AIService aiService;
    private final TimezoneService timezoneService;
    private final RoomHistoryManager historyManager;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserver;
    private final String accessToken;
    private final String commandRoomId;
    private final Path persistenceFile;
    private final Path summaryPersistenceFile;
    private final Path lastPublicPreferenceFile;
    private final Path tldrPublicPreferenceFile;
    private final Path lastReadInfoPersistenceFile;

    public AutoLastService(MatrixClient matrixClient, LastMessageService lastMessageService,
            AIService aiService, TimezoneService timezoneService, RoomHistoryManager historyManager,
            HttpClient httpClient, ObjectMapper mapper, String homeserver, String accessToken, String commandRoomId) {
        this.matrixClient = matrixClient;
        this.lastMessageService = lastMessageService;
        this.aiService = aiService;
        this.timezoneService = timezoneService;
        this.historyManager = historyManager;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.commandRoomId = commandRoomId;
        this.persistenceFile = Paths.get("autolast_enabled_users.json");
        this.summaryPersistenceFile = Paths.get("autotldr_enabled_users.json");
        this.lastPublicPreferenceFile = Paths.get("autolast_public_preferences.json");
        this.tldrPublicPreferenceFile = Paths.get("autotldr_public_preferences.json");
        this.lastReadInfoPersistenceFile = Paths.get("autolast_read_info.json");

        // Load persisted enabled users
        loadEnabledUsers();
        loadPublicPreferences();
        loadLastReadInfo();
    }

    /**
     * Toggles the feature for a user (backward compatibility).
     */
    public void toggleAutoLast(String userId, String roomId) {
        toggleAutoLast(userId, roomId, false);
    }

    /**
     * Toggles the feature for a user.
     */
    public void toggleAutoLast(String userId, String roomId, boolean isPublic) {
        if (enabledUsers.contains(userId)) {
            enabledUsers.remove(userId);
            userLastPublicPref.remove(userId);
            matrixClient.sendText(roomId, "Auto-!last disabled.");
        } else {
            enabledUsers.add(userId);
            userLastPublicPref.put(userId, isPublic);
            String message = isPublic 
                ? "Auto-!last enabled. I will send your last message to this channel when you read the export room after being away for over an hour with over 75 unread messages."
                : "Auto-!last enabled. I will DM you your last message when you read the export room after being away for over an hour with over 75 unread messages.";
            matrixClient.sendText(roomId, message);
        }
        saveEnabledUsers();
        savePublicPreferences();
    }

    /**
     * Toggles the tldr feature for a user (backward compatibility).
     */
    public void toggleAutoTldr(String userId, String roomId) {
        toggleAutoTldr(userId, roomId, false);
    }

    /**
     * Toggles the tldr feature for a user.
     */
    public void toggleAutoTldr(String userId, String roomId, boolean isPublic) {
        if (enabledTldrUsers.contains(userId)) {
            enabledTldrUsers.remove(userId);
            userTldrPublicPref.remove(userId);
            matrixClient.sendText(roomId, "Auto-!autotldr disabled.");
        } else {
            enabledTldrUsers.add(userId);
            userTldrPublicPref.put(userId, isPublic);
            String message = isPublic
                ? "Auto-!autotldr enabled. I will send an AI TLDR to this channel when you read the export room after being away for over an hour with over 75 unread messages."
                : "Auto-!autotldr enabled. I will DM you an AI TLDR when you read the export room after being away for over an hour with over 75 unread messages.";
            matrixClient.sendText(roomId, message);
        }
        saveEnabledUsers();
        savePublicPreferences();
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

        // Skip processing on first sync to avoid overwriting persistent state with old receipts from before bot started
        if (!firstSyncProcessed) {
            firstSyncProcessed = true;
            System.out.println("First sync processed - will start tracking read receipts from now using persistent state");
            return;
        }

        for (JsonNode event : ephemeralEvents) {
            if ("m.receipt".equals(event.path("type").asText())) {
                processReceiptEvent(roomId, event.path("content"), exportRoomId);
            }
        }
    }

    private void processReceiptEvent(String roomId, JsonNode content, String exportRoomId) {
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
                boolean tldrEnabled = enabledTldrUsers.contains(userId);
                
                if (!lastEnabled && !tldrEnabled)
                    continue;

                long now = System.currentTimeMillis();
                long ts = readNode.path(userId).path("ts").asLong(0);
                RoomHistoryManager.EventInfo currentReadInfo = new RoomHistoryManager.EventInfo(eventId, ts);
                RoomHistoryManager.EventInfo previousReadInfo = lastReadInfo.get(userId);

                // Update state for next trigger check, but we need the previous info to check gaps
                if (previousReadInfo == null || previousReadInfo.eventId.equals(eventId)) {
                    lastReadInfo.put(userId, currentReadInfo);
                    saveLastReadInfo();
                    continue;
                }

                // Calculate gap and unread count once if any feature is enabled
                if (ts - previousReadInfo.timestamp >= 3600000) {
                    int unreadCount = historyManager.countUnreadMessages(roomId, previousReadInfo.eventId);
                    if (unreadCount >= 75) {
                        // 2. Handle Auto-Last
                        if (lastEnabled) {
                            triggerLastMessage(exportRoomId, userId, previousReadInfo, roomId);
                            lastTriggerTime.put(userId, now);
                        }

                        // 3. Handle Auto-TLDR
                        if (tldrEnabled) {
                            triggerTldr(exportRoomId, userId, previousReadInfo, roomId);
                            lastTldrTriggerTime.put(userId, now);
                        }
                    }
                }

                // Update state for next time
                lastReadInfo.put(userId, currentReadInfo);
                saveLastReadInfo();
            }
        }
    }

    private void triggerLastMessage(String exportRoomId, String userId, RoomHistoryManager.EventInfo previousReadInfo, String roomId) {
        boolean isPublic = userLastPublicPref.getOrDefault(userId, false);
        String targetRoomId = isPublic ? commandRoomId : findDirectMessageRoom(userId);
        
        if (targetRoomId != null) {
            System.out.println("Triggering Auto-Last for " + userId + " (public: " + isPublic + ")");
            // We run this in a separate thread to not block the sync loop
            new Thread(() -> lastMessageService.sendLastMessageAndReadReceipt(exportRoomId, userId, targetRoomId,
                    previousReadInfo)).start();
        } else {
            System.out.println("Could not find " + (isPublic ? "room" : "DM room") + " for auto-last user: " + userId);
        }
    }

private void triggerTldr(String exportRoomId, String userId, RoomHistoryManager.EventInfo previousReadInfo, String roomId) {
        boolean isPublic = userTldrPublicPref.getOrDefault(userId, false);
        String targetRoomId = isPublic ? commandRoomId : findDirectMessageRoom(userId);
         
         if (targetRoomId != null) {
             System.out.println("Triggering Auto-TLDR for " + userId + " (public: " + isPublic + ")");
             java.time.ZoneId zoneId = timezoneService.getZoneIdForUser(userId);
             if (zoneId == null) {
                 zoneId = java.time.ZoneId.of("UTC");
                 if (targetRoomId != null) {
                     matrixClient.sendNotice(targetRoomId, "Timezone not set. Using UTC by default. " +
                             "Set it with !timezone <TZ> or your local time: !timezone 1:14am or !timezone 14:30");
                 }
             }
             final java.time.ZoneId finalZoneId = zoneId;
             
             new Thread(() -> {
                 try {
                     aiService.queryAIUnreadFiltered(targetRoomId, exportRoomId, userId, finalZoneId, null,
                            AIService.Prompts.TLDR_PREFIX, new java.util.concurrent.atomic.AtomicBoolean(false),
                            previousReadInfo != null ? previousReadInfo.eventId : null);
                } catch (Exception e) {
                    System.err.println("Error running auto-tldr: " + e.getMessage());
                }
            }).start();
        } else {
            System.out.println("Could not find " + (isPublic ? "room" : "DM room") + " for auto-tldr user: " + userId);
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
                enabledTldrUsers.addAll(Arrays.asList(users));
                System.out.println("Loaded " + users.length + " autotldr enabled users from persistence");
            } catch (IOException e) {
                System.err.println("Error loading autotldr enabled users: " + e.getMessage());
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

            String[] tldrUsers = enabledTldrUsers.toArray(new String[0]);
            String tldrContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tldrUsers);
            Files.writeString(summaryPersistenceFile, tldrContent);
        } catch (IOException e) {
            System.err.println("Error saving enabled users: " + e.getMessage());
        }
    }

    /**
     * Load public preferences from persistence files.
     */
    private void loadPublicPreferences() {
        // Load autolast public preferences
        if (Files.exists(lastPublicPreferenceFile)) {
            try {
                String content = Files.readString(lastPublicPreferenceFile);
                Map<String, Boolean> preferences = mapper.readValue(content, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Boolean>>() {});
                userLastPublicPref.putAll(preferences);
                System.out.println("Loaded " + preferences.size() + " autolast public preferences from persistence");
            } catch (IOException e) {
                System.err.println("Error loading autolast public preferences: " + e.getMessage());
            }
        }

        // Load autotldr public preferences
        if (Files.exists(tldrPublicPreferenceFile)) {
            try {
                String content = Files.readString(tldrPublicPreferenceFile);
                Map<String, Boolean> preferences = mapper.readValue(content, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Boolean>>() {});
                userTldrPublicPref.putAll(preferences);
                System.out.println("Loaded " + preferences.size() + " autotldr public preferences from persistence");
            } catch (IOException e) {
                System.err.println("Error loading autotldr public preferences: " + e.getMessage());
            }
        }
    }

    /**
     * Save public preferences to persistence files.
     */
    private void savePublicPreferences() {
        try {
            String lastContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(userLastPublicPref);
            Files.writeString(lastPublicPreferenceFile, lastContent);

            String tldrContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(userTldrPublicPref);
            Files.writeString(tldrPublicPreferenceFile, tldrContent);
        } catch (IOException e) {
            System.err.println("Error saving public preferences: " + e.getMessage());
        }
    }

    private void loadLastReadInfo() {
        if (Files.exists(lastReadInfoPersistenceFile)) {
            try {
                String content = Files.readString(lastReadInfoPersistenceFile);
                Map<String, RoomHistoryManager.EventInfo> info = mapper.readValue(content, new com.fasterxml.jackson.core.type.TypeReference<Map<String, RoomHistoryManager.EventInfo>>() {});
                lastReadInfo.putAll(info);
                System.out.println("Loaded " + info.size() + " last read infos from persistence");
            } catch (IOException e) {
                System.err.println("Error loading last read info: " + e.getMessage());
            }
        }
    }

    private void saveLastReadInfo() {
        try {
            String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(lastReadInfo);
            Files.writeString(lastReadInfoPersistenceFile, content);
        } catch (IOException e) {
            System.err.println("Error saving last read info: " + e.getMessage());
        }
    }
}