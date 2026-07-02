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
    private final Set<String> enabledTopicListUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> userLastPublicPref = new ConcurrentHashMap<>();
    private final Map<String, Boolean> userTldrPublicPref = new ConcurrentHashMap<>();
    private final Map<String, Boolean> userTopicListPublicPref = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTldrTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTopicListTriggerTime = new ConcurrentHashMap<>();
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
    private final Path persistenceFile;
    private final Path summaryPersistenceFile;
    private final Path topicListPersistenceFile;
    private final Path lastPublicPreferenceFile;
    private final Path tldrPublicPreferenceFile;
    private final Path topicListPublicPreferenceFile;

    public AutoLastService(MatrixClient matrixClient, LastMessageService lastMessageService,
            AIService aiService, TimezoneService timezoneService, RoomHistoryManager historyManager,
            HttpClient httpClient, ObjectMapper mapper, String homeserver, String accessToken) {
        this.matrixClient = matrixClient;
        this.lastMessageService = lastMessageService;
        this.aiService = aiService;
        this.timezoneService = timezoneService;
        this.historyManager = historyManager;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.persistenceFile = Paths.get("autolast_enabled_users.json");
        this.summaryPersistenceFile = Paths.get("autotldr_enabled_users.json");
        this.topicListPersistenceFile = Paths.get("autotopiclist_enabled_users.json");
        this.lastPublicPreferenceFile = Paths.get("autolast_public_preferences.json");
        this.tldrPublicPreferenceFile = Paths.get("autotldr_public_preferences.json");
        this.topicListPublicPreferenceFile = Paths.get("autotopiclist_public_preferences.json");

        // Load persisted enabled users
        loadEnabledUsers();
        loadPublicPreferences();
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
     * Toggles the topiclist feature for a user (backward compatibility).
     */
    public void toggleAutoTopicList(String userId, String roomId) {
        toggleAutoTopicList(userId, roomId, false);
    }

    /**
     * Toggles the topiclist feature for a user.
     */
    public void toggleAutoTopicList(String userId, String roomId, boolean isPublic) {
        if (enabledTopicListUsers.contains(userId)) {
            enabledTopicListUsers.remove(userId);
            userTopicListPublicPref.remove(userId);
            matrixClient.sendText(roomId, "Auto-!topiclist disabled.");
        } else {
            enabledTopicListUsers.add(userId);
            userTopicListPublicPref.put(userId, isPublic);
            String message = isPublic
                ? "Auto-!topiclist enabled. I will send a topic list to this channel when you read the export room after being away for over an hour with over 75 unread messages."
                : "Auto-!topiclist enabled. I will DM you a topic list when you read the export room after being away for over an hour with over 75 unread messages.";
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

        // Skip processing on first sync to avoid triggering on old receipts from before bot started
        if (!firstSyncProcessed) {
            firstSyncProcessed = true;
            System.out.println("First sync processed - will start tracking read receipts from now");
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
                boolean topicListEnabled = enabledTopicListUsers.contains(userId);
                
                if (!lastEnabled && !tldrEnabled && !topicListEnabled)
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
                    // Threshold: > 1 hour gap
                    if (now - lastTrigger >= 3600000) {
                        int unreadCount = historyManager.countUnreadMessages(roomId, previousReadInfo.eventId);
                        if (unreadCount >= 75) {
                            triggerLastMessage(exportRoomId, userId, previousReadInfo, roomId);
                            lastTriggerTime.put(userId, now);
                        }
                    }
                }

                // 3. Handle Auto-TLDR
                if (tldrEnabled) {
                    long lastTldrTrigger = lastTldrTriggerTime.getOrDefault(userId, 0L);
                    // Threshold: > 1 hour gap
                    if (now - lastTldrTrigger >= 3600000) {
                        int unreadCount = historyManager.countUnreadMessages(roomId, previousReadInfo.eventId);
                        if (unreadCount >= 75) {
                            triggerTldr(exportRoomId, userId, previousReadInfo, roomId);
                            lastTldrTriggerTime.put(userId, now);
                        }
                    }
                }

                // 4. Handle Auto-TopicList
                if (topicListEnabled) {
                    long lastTopicListTrigger = lastTopicListTriggerTime.getOrDefault(userId, 0L);
                    // Threshold: > 1 hour gap
                    if (now - lastTopicListTrigger >= 3600000) {
                        int unreadCount = historyManager.countUnreadMessages(roomId, previousReadInfo.eventId);
                        if (unreadCount >= 75) {
                            triggerTopicList(exportRoomId, userId, previousReadInfo, roomId);
                            lastTopicListTriggerTime.put(userId, now);
                        }
                    }
                }

                // Update state for next time
                lastReadInfo.put(userId, currentReadInfo);
            }
        }
    }

    private void triggerLastMessage(String exportRoomId, String userId, RoomHistoryManager.EventInfo previousReadInfo, String roomId) {
        boolean isPublic = userLastPublicPref.getOrDefault(userId, false);
        String targetRoomId = isPublic ? roomId : findDirectMessageRoom(userId);
        
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
         String targetRoomId = isPublic ? roomId : findDirectMessageRoom(userId);
         
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
                     aiService.queryAIUnread(targetRoomId, exportRoomId, userId, finalZoneId, null,
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

    private void triggerTopicList(String exportRoomId, String userId, RoomHistoryManager.EventInfo previousReadInfo, String roomId) {
        boolean isPublic = userTopicListPublicPref.getOrDefault(userId, false);
        String targetRoomId = isPublic ? roomId : findDirectMessageRoom(userId);
        
        if (targetRoomId != null) {
            System.out.println("Triggering Auto-TopicList for " + userId + " (public: " + isPublic + ")");
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
                    aiService.queryAIUnread(targetRoomId, exportRoomId, userId, finalZoneId, null,
                           AIService.Prompts.TOPICLIST_PREFIX, new java.util.concurrent.atomic.AtomicBoolean(false),
                           previousReadInfo != null ? previousReadInfo.eventId : null);
               } catch (Exception e) {
                   System.err.println("Error running auto-topiclist: " + e.getMessage());
               }
           }).start();
        } else {
            System.out.println("Could not find " + (isPublic ? "room" : "DM room") + " for auto-topiclist user: " + userId);
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

        // Load autotopiclist enabled users
        if (Files.exists(topicListPersistenceFile)) {
            try {
                String content = Files.readString(topicListPersistenceFile);
                String[] users = mapper.readValue(content, String[].class);
                enabledTopicListUsers.addAll(Arrays.asList(users));
                System.out.println("Loaded " + users.length + " autotopiclist enabled users from persistence");
            } catch (IOException e) {
                System.err.println("Error loading autotopiclist enabled users: " + e.getMessage());
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

            String[] topicListUsers = enabledTopicListUsers.toArray(new String[0]);
            String topicListContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(topicListUsers);
            Files.writeString(topicListPersistenceFile, topicListContent);
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

        // Load autotopiclist public preferences
        if (Files.exists(topicListPublicPreferenceFile)) {
            try {
                String content = Files.readString(topicListPublicPreferenceFile);
                Map<String, Boolean> preferences = mapper.readValue(content, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Boolean>>() {});
                userTopicListPublicPref.putAll(preferences);
                System.out.println("Loaded " + preferences.size() + " autotopiclist public preferences from persistence");
            } catch (IOException e) {
                System.err.println("Error loading autotopiclist public preferences: " + e.getMessage());
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

            String topicListContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(userTopicListPublicPref);
            Files.writeString(topicListPublicPreferenceFile, topicListContent);
        } catch (IOException e) {
            System.err.println("Error saving public preferences: " + e.getMessage());
        }
    }
}