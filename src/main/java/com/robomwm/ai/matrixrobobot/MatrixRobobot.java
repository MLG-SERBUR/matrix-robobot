package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Architecture:
 * - MatrixRobobot: Sync loop, !last command, room management
 * - MatrixClient: Matrix protocol HTTP interactions
 * - RoomHistoryManager: Chat history fetching and pagination
 * - LastMessageService: !last command implementation
 * - RoomManagementService: Join/leave/cleanup logic
 * - CommandDispatcher: Routing for other commands (export, search, etc.)
 */

public class MatrixRobobot {

    public static class Config {
        public String homeserver;
        public String accessToken;
        public String commandRoomId;
        public String exportRoomId;
        public String arliApiKey;
        public String cerebrasApiKey;
    }

    private static final Map<String, AtomicBoolean> runningOperations = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config.json";
        Config config = loadConfig(configPath);

        if (config == null) {
            System.err.println("Failed to load configuration from: " + configPath);
            System.exit(2);
        }

        String url = config.homeserver.endsWith("/")
                ? config.homeserver.substring(0, config.homeserver.length() - 1)
                : config.homeserver;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(120))
                .build();
        ObjectMapper mapper = new ObjectMapper();

        // Initialize services
        MatrixClient matrixClient = new MatrixClient(client, mapper, url, config.accessToken);
        RoomHistoryManager historyManager = new RoomHistoryManager(client, mapper, url, config.accessToken);
        LastMessageService lastMessageService = new LastMessageService(matrixClient, historyManager);
        RoomManagementService roomMgmt = new RoomManagementService(matrixClient, client, mapper, url,
                config.accessToken);
        TextSearchService textSearchService = new TextSearchService(matrixClient, historyManager, client, mapper, url,
                config, runningOperations);
        AIService aiService = new AIService(client, mapper, url, config.accessToken, config.arliApiKey,
                config.cerebrasApiKey);
        SemanticSearchService semanticSearchService = new SemanticSearchService(client, mapper, url,
                config.accessToken);
        TimezoneService timezoneService = new TimezoneService(mapper);
        CommandDispatcher dispatcher = new CommandDispatcher(client, mapper, url, config.accessToken,
                config.commandRoomId, config.exportRoomId, historyManager, runningOperations, textSearchService,
                aiService, semanticSearchService, timezoneService);

        // NEW: AutoLastService with explicit HttpClient passed
        AutoLastService autoLastService = new AutoLastService(matrixClient, lastMessageService, aiService,
                timezoneService, client, mapper, url, config.accessToken);
        
        // NEW: PleadService for 🥺 reactions
        PleadService pleadService = new PleadService(matrixClient);

        String userId = matrixClient.getUserId();

        String since = null;
        try {
            HttpRequest initSync = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/_matrix/client/v3/sync?timeout=0"))
                    .header("Authorization", "Bearer " + config.accessToken)
                    .GET()
                    .build();
            HttpResponse<String> initResp = client.send(initSync, HttpResponse.BodyHandlers.ofString());
            if (initResp.statusCode() == 200) {
                JsonNode initRoot = mapper.readTree(initResp.body());
                since = initRoot.path("next_batch").asText(null);
                System.out.println("Primed since token: " + since);
            }
        } catch (Exception e) {
            System.out.println("Initial sync failed: " + e.getMessage());
        }

        System.out.println("Starting /sync loop");
        System.out.println("Command room: " + config.commandRoomId);
        System.out.println("Export room: " + config.exportRoomId);

        roomMgmt.cleanupAbandonedDMs(config.commandRoomId, config.exportRoomId);

        long currentSleepMs = 2000;
        final long initialBackoffMs = 60000;
        final long maxBackoffMs = 300000;

        while (true) {
            try {
                String syncUrl = url + "/_matrix/client/v3/sync?timeout=30000"
                        + (since != null ? "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8) : "");

                HttpRequest syncReq = HttpRequest.newBuilder()
                        .uri(URI.create(syncUrl))
                        .header("Authorization", "Bearer " + config.accessToken)
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build();

                HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
                if (syncResp.statusCode() != 200) {
                    System.out.println("/sync returned: " + syncResp.statusCode());
                    Thread.sleep(2000);
                    continue;
                }

                JsonNode root = mapper.readTree(syncResp.body());
                since = root.path("next_batch").asText(since);

                // Handle invites
                JsonNode inviteRooms = root.path("rooms").path("invite");
                Iterator<String> inviteRoomIds = inviteRooms.fieldNames();
                while (inviteRoomIds.hasNext()) {
                    String roomId = inviteRoomIds.next();
                    System.out.println("Invited to room: " + roomId);
                    roomMgmt.handleInvitedRoom(roomId);
                }

                // Handle leaves
                JsonNode leaveRooms = root.path("rooms").path("leave");
                Iterator<String> leaveRoomIds = leaveRooms.fieldNames();
                while (leaveRoomIds.hasNext()) {
                    String roomId = leaveRoomIds.next();
                    System.out.println("User left room: " + roomId);
                    roomMgmt.handleUserLeftRoom(roomId, config.commandRoomId, config.exportRoomId);
                }

                // Process rooms
                JsonNode rooms = root.path("rooms").path("join");
                Iterator<String> roomIds = rooms.fieldNames();
                while (roomIds.hasNext()) {
                    String roomId = roomIds.next();
                    JsonNode roomNode = rooms.path(roomId);

                    // NEW: Process Ephemeral Events (Read Receipts)
                    JsonNode ephemeralEvents = roomNode.path("ephemeral").path("events");
                    autoLastService.processEphemeralEvents(roomId, ephemeralEvents, config.exportRoomId);

                    JsonNode timelineNode = roomNode.path("timeline");
                    String prevBatch = timelineNode.path("prev_batch").asText(null);
                    JsonNode timeline = timelineNode.path("events");

                    if (timeline.isArray()) {
                        for (JsonNode ev : timeline) {
                            if (!"m.room.message".equals(ev.path("type").asText(null)))
                                continue;

                            String body = ev.path("content").path("body").asText(null);
                            String sender = ev.path("sender").asText(null);
                            if (body == null)
                                continue;

                            String eventId = ev.path("event_id").asText(null);
                            String trimmed = body.trim();
                            String responseRoomId = roomId;

                            if (userId != null && userId.equals(sender))
                                continue;
                            
                            // Process emojis via PleadService
                            pleadService.processMessage(roomId, eventId, body);

                            // PRIMARY: !last command
                            if ("!last".equals(trimmed)) {
                                System.out.println("Received !last command in " + roomId + " from " + sender);
                                final String finalSender = sender;
                                new Thread(() -> lastMessageService.sendLastMessageAndReadReceipt(config.exportRoomId,
                                        finalSender, responseRoomId)).start();
                            }
                            // NEW: !autolast command
                            else if ("!autolast".equals(trimmed)) {
                                System.out.println("Received !autolast command from " + sender);
                                autoLastService.toggleAutoLast(sender, responseRoomId);
                            }
                            // NEW: !autotldr command
                            else if ("!autotldr".equals(trimmed)) {
                                System.out.println("Received !autotldr command from " + sender);
                                autoLastService.toggleAutoTldr(sender, responseRoomId);
                            }
                            // NEW: !plead command
                            else if ("!plead".equals(trimmed)) {
                                System.out.println("Received !plead command from " + sender);
                                pleadService.togglePlead(responseRoomId);
                            }
                            // !ping for diagnostics
                            else if ("!ping".equals(trimmed)) {
                                System.out.println("Received !ping command in " + roomId + " from " + sender);
                                long messageTimestamp = ev.path("origin_server_ts").asLong(System.currentTimeMillis());
                                long latencyMs = System.currentTimeMillis() - messageTimestamp;
                                matrixClient.sendText(responseRoomId, "Pong! (ping took " + latencyMs + " ms)");
                            }
                            // All other commands
                            else {
                                dispatcher.dispatchCommand(trimmed, roomId, sender, prevBatch, responseRoomId,
                                        config.exportRoomId);
                            }
                        }
                    }
                }
                currentSleepMs = 2000; // Reset backoff on success

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error during sync loop (" + e.getClass().getSimpleName() + "): " + e.getMessage());
                if (e.getCause() != null) {
                    System.err.println(
                            "  Cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
                }
                e.printStackTrace();
                try {
                    System.out.println("Sleeping for " + (currentSleepMs / 1000) + " seconds before retrying...");
                    Thread.sleep(currentSleepMs);
                    if (currentSleepMs < initialBackoffMs) {
                        currentSleepMs = initialBackoffMs;
                    } else {
                        currentSleepMs = Math.min(maxBackoffMs, currentSleepMs * 2);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private static Config loadConfig(String configPath) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(configPath)));
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(content, Config.class);
        } catch (Exception e) {
            System.err.println("Error loading config from " + configPath + ": " + e.getMessage());
            return null;
        }
    }
}