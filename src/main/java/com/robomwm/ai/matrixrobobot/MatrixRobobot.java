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
        public String groqApiKey;
        public String openrouterApiKey;
        public String freeLlmApiKey;
        public String ollamaProxyApiKey;
        public String ollamaProxyUrl;
        public java.util.List<String> arliModels;
        public java.util.List<String> cerebrasModels;
        public java.util.List<String> groqModels;
        public java.util.List<String> openrouterModels;
        public java.util.List<String> freeLlmModels;
        public java.util.List<String> ollamaProxyModels;
        public String imageCaptionBackend;
        public String imageCaptionModel;
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
                .connectTimeout(Duration.ofSeconds(1200))
                .followRedirects(HttpClient.Redirect.NORMAL)
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
                config.cerebrasApiKey, config.groqApiKey, config.openrouterApiKey, config.freeLlmApiKey,
                config.ollamaProxyApiKey, config.ollamaProxyUrl,
                config.arliModels, config.cerebrasModels, config.groqModels, config.openrouterModels, 
                config.freeLlmModels, config.ollamaProxyModels);
        ImageFetcher imageFetcher = new ImageFetcher(client, mapper, url, config.accessToken);
        VisionAIService visionAIService;
        if ("OLLAMA".equalsIgnoreCase(config.imageCaptionBackend) || "OLLAMA_PROXY".equalsIgnoreCase(config.imageCaptionBackend)) {
            visionAIService = new OllamaVisionAIService(client, mapper, url, config.accessToken,
                    config.arliApiKey, config.groqApiKey, config.openrouterApiKey, config.freeLlmApiKey,
                    config.ollamaProxyApiKey, config.ollamaProxyUrl, imageFetcher, config.imageCaptionModel,
                    config.arliModels, config.cerebrasModels, config.groqModels, config.openrouterModels,
                    config.freeLlmModels, config.ollamaProxyModels);
        } else {
            visionAIService = new VisionAIService(client, mapper, url, config.accessToken,
                    config.arliApiKey, config.groqApiKey, config.openrouterApiKey, config.freeLlmApiKey,
                    config.ollamaProxyApiKey, config.ollamaProxyUrl, imageFetcher,
                    config.arliModels, config.cerebrasModels, config.groqModels, config.openrouterModels,
                    config.freeLlmModels, config.ollamaProxyModels);
        }
        SemanticSearchService semanticSearchService = new SemanticSearchService(client, mapper, url,
                config.accessToken);
        TimezoneService timezoneService = new TimezoneService(mapper);
        CommandDispatcher dispatcher = new CommandDispatcher(client, mapper, url, config.accessToken,
                config.commandRoomId, config.exportRoomId, historyManager, runningOperations, textSearchService,
                aiService, visionAIService, semanticSearchService, timezoneService, config.arliApiKey);

        // NEW: AutoLastService with explicit HttpClient passed
        AutoLastService autoLastService = new AutoLastService(matrixClient, lastMessageService, aiService,
                timezoneService, historyManager, client, mapper, url, config.accessToken, config.commandRoomId);
        
        // NEW: PleadService for 🥺 reactions
        PleadService pleadService = new PleadService(matrixClient);
        pleadService.setLifeReactService(new LifeReactService(matrixClient));
        
        // NEW: OkReactionService for consecutive "ok" reactions
        OkReactionService okReactionService = new OkReactionService(matrixClient);

        String userId = matrixClient.getUserId();

        // Skip initial sync - we only care about messages after bot starts
        String since = null;
        System.out.println("Starting /sync loop (skipping initial sync - only processing new messages)");
        System.out.println("Command room: " + config.commandRoomId);
        System.out.println("Export room: " + config.exportRoomId);

        roomMgmt.cleanupAbandonedDMs(config.commandRoomId, config.exportRoomId);

        long currentSleepMs = 2000;
        final long initialBackoffMs = 60000;
        final long maxBackoffMs = 300000;

        while (true) {
            try {
                // Use a lightweight filter to minimize sync payload and avoid 504 timeouts
                String filter = since == null 
                    ? "{\"room\":{\"timeline\":{\"limit\":1},\"state\":{\"lazy_load_members\":true}},\"presence\":{\"not_types\":[\"m.presence\"]}}"
                    : "";
                String syncUrl = url + "/_matrix/client/v3/sync?timeout=30000"
                        + (since != null ? "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8) : "")
                        + (!filter.isEmpty() ? "&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8) : "");

                HttpRequest syncReq = HttpRequest.newBuilder()
                        .uri(URI.create(syncUrl))
                        .header("Authorization", "Bearer " + config.accessToken)
                        .timeout(Duration.ofSeconds(300))
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
                            String msgtype = ev.path("content").path("msgtype").asText(null);
                            String trimmed = body.trim();
                            String responseRoomId = roomId;

                            if (userId != null && userId.equals(sender))
                                continue;
                            
                            // Process emojis via PleadService
                            pleadService.processMessage(roomId, eventId, body, sender);
                            
                            // Process consecutive "ok" messages
                            okReactionService.processMessage(roomId, eventId, body, sender, msgtype);

                            // PRIMARY: !last command
                            if ("!last".equals(trimmed)) {
                                System.out.println("Received !last command in " + roomId + " from " + sender);
                                final String finalSender = sender;
                                new Thread(() -> lastMessageService.sendLastMessageAndReadReceipt(config.exportRoomId,
                                        finalSender, responseRoomId)).start();
                            }
                            // NEW: !autolast command
                            else if (trimmed.startsWith("!autolast")) {
                                System.out.println("Received !autolast command from " + sender);
                                boolean isPublic = trimmed.contains("public");
                                autoLastService.toggleAutoLast(sender, responseRoomId, isPublic);
                            }
                            // NEW: !autotldr command
                            else if (trimmed.startsWith("!autotldr")) {
                                System.out.println("Received !autotldr command from " + sender);
                                boolean isPublic = trimmed.contains("public");
                                autoLastService.toggleAutoTldr(sender, responseRoomId, isPublic);
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
                                boolean handled = dispatcher.dispatchCommand(trimmed, roomId, sender, prevBatch, responseRoomId,
                                        config.exportRoomId);
                                if (!handled) {
                                    // Check if it's a contextual reply
                                    JsonNode relatesTo = ev.path("content").path("m.relates_to");
                                    JsonNode inReplyTo = relatesTo.path("m.in_reply_to");
                                    String replyToEventId = inReplyTo.path("event_id").asText(null);
                                    
                                    if (replyToEventId != null) {
                                        JsonNode originalEvent = matrixClient.getEvent(roomId, replyToEventId);
                                        if (originalEvent != null) {
                                            String origSender = originalEvent.path("sender").asText(null);
                                            if (userId != null && userId.equals(origSender)) {
                                                JsonNode context = originalEvent.path("content").path("ai.matrixrobobot.context");
                                                if (!context.isMissingNode()) {
                                                    String ctxStart = context.path("startEventId").asText(null);
                                                    String ctxEnd = context.path("endEventId").asText(null);
                                                    if ("null".equals(ctxStart)) ctxStart = null;
                                                    if ("null".equals(ctxEnd)) ctxEnd = null;
                                                    if (ctxEnd == null && ctxStart != null) {
                                                        ctxEnd = ctxStart;
                                                    }
                                                    boolean ctxFwd = context.path("forward").asBoolean(false);
                                                    String ctxRoom = context.path("exportRoomId").asText(config.exportRoomId);
                                                    boolean ctxFiltered = context.path("filtered").asBoolean(false);
                                                    
                                                    System.out.println("Contextual reply detected (filtered=" + ctxFiltered + ")! Executing query...");
                                                    AtomicBoolean abortFlag = new AtomicBoolean(false);
                                                    runningOperations.put(sender, abortFlag);
                                                    
                                                    final int fHours = -1;
                                                    final int fMax = Integer.MAX_VALUE;
                                                    final String fStart = ctxStart;
                                                    final boolean fFwd = ctxFwd;
                                                    final String fRoom = ctxRoom;
                                                    final boolean fFiltered = ctxFiltered;
                                                    
                                                    // Strip Matrix reply fallback
                                                    String actualReply = trimmed;
                                                    if (actualReply.startsWith("> ")) {
                                                        int fallbackEnd = actualReply.indexOf("\n\n");
                                                        if (fallbackEnd != -1) {
                                                            actualReply = actualReply.substring(fallbackEnd + 2).trim();
                                                        } else {
                                                            // fallback didn't have \n\n, try to strip all lines starting with >
                                                            String[] lines = actualReply.split("\n");
                                                            StringBuilder sb = new StringBuilder();
                                                            for (String line : lines) {
                                                                if (!line.startsWith("> ")) {
                                                                    sb.append(line).append("\n");
                                                                }
                                                            }
                                                            actualReply = sb.toString().trim();
                                                        }
                                                    }
                                                    final String fQuestion = actualReply;
                                                    
                                                    new Thread(() -> {
                                                        try {
                                                            java.time.ZoneId zoneId = timezoneService.getZoneIdForUser(sender);
                                                            if (zoneId == null) zoneId = java.time.ZoneId.of("UTC");
                                                            
                                                            if (fFiltered) {
                                                                aiService.queryAIFiltered(responseRoomId, fRoom, fHours, null, fQuestion, fStart, fFwd, zoneId, fMax, AIService.Prompts.ASK_PREFIX, abortFlag, AIService.Backend.AUTO);
                                                            } else {
                                                                aiService.queryAI(responseRoomId, fRoom, fHours, null, fQuestion, fStart, fFwd, zoneId, fMax, AIService.Prompts.ASK_PREFIX, abortFlag, AIService.Backend.AUTO);
                                                            }
                                                        } finally {
                                                            runningOperations.remove(sender);
                                                        }
                                                    }).start();
                                                }
                                            }
                                        }
                                    }
                                }
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
