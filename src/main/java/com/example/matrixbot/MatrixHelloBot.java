package com.example.matrixbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.parser.Parser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

public class MatrixHelloBot {
    
    private static class Config {
        public String homeserver;
        public String accessToken;
        public String commandRoomId;
        public String exportRoomId;
        public String arliApiKey;
        public String cerebrasApiKey;
    }
    
    // Track running search operations by user ID
    private static final java.util.Map<String, AtomicBoolean> runningOperations = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Centralized prompt configuration
    private static class Prompts {
        public static final String SYSTEM_OVERVIEW = "You provide high level overview of a chat log.";
        
        public static final String QUESTION_PREFIX = "Given the following chat logs, answer the question: '";
        public static final String QUESTION_SUFFIX = "'\\n\\n";
        
        public static final String OVERVIEW_PREFIX = "Give a high level overview of the following chat logs. Use only a title and timestamp for each topic and only include one or more chat messages verbatim (with username) as bullet points for each topic; bias to include discovered solutions or interesting resources. Don't use table format. Then summarize with bullet points all of the chat at end:\\n\\n";
    }
    
    // Helper method to build the user prompt
    private static String buildPrompt(String question, java.util.List<String> logs) {
        String logsStr = String.join("\\n", logs);
        if (question != null && !question.isEmpty()) {
            return Prompts.QUESTION_PREFIX + question + Prompts.QUESTION_SUFFIX + logsStr;
        } else {
            return Prompts.OVERVIEW_PREFIX + logsStr;
        }
    }
    
    // Helper method to build the messages list for the AI API
    private static java.util.List<Map<String, String>> buildMessages(String prompt) {
        java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content", Prompts.SYSTEM_OVERVIEW));
        messages.add(Map.of("role", "user", "content", prompt));
        return messages;
    }
    
    // Helper method to append message link to AI answer
    private static String appendMessageLink(String aiAnswer, String exportRoomId, String firstEventId) {
        if (firstEventId != null) {
            String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + firstEventId;
            return aiAnswer + "\n\n" + messageLink;
        }
        return aiAnswer;
    }
    
    public static void main(String[] args) throws Exception {
        // Load configuration from file
        String configPath = args.length > 0 ? args[0] : "config.json";
        Config config = loadConfig(configPath);
        
        if (config == null) {
            System.err.println("Failed to load configuration from: " + configPath);
            System.exit(2);
        }

        String url = config.homeserver.endsWith("/") ? config.homeserver.substring(0, config.homeserver.length() - 1) : config.homeserver;
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        // attempt to discover our own user id to avoid replying to self
        String userId = null;
        try {
            HttpRequest whoami = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/_matrix/client/v3/account/whoami"))
                    .header("Authorization", "Bearer " + config.accessToken)
                    .GET()
                    .build();
            HttpResponse<String> whoamiResp = client.send(whoami, HttpResponse.BodyHandlers.ofString());
            if (whoamiResp.statusCode() == 200) {
                JsonNode whoamiJson = mapper.readTree(whoamiResp.body());
                userId = whoamiJson.path("user_id").asText(null);
                System.out.println("Detected user id: " + userId);
            } else {
                System.out.println("whoami returned: " + whoamiResp.statusCode());
            }
        } catch (Exception e) {
            System.out.println("whoami failed: " + e.getMessage());
        }

        String since = null;
        // Perform an initial short /sync to obtain a since token so we don't re-process
        // historical events that happened before this process started.
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
            } else {
                System.out.println("Initial sync returned: " + initResp.statusCode());
            }
        } catch (Exception e) {
            System.out.println("Initial sync failed: " + e.getMessage());
        }

        System.out.println("Starting /sync loop");
        System.out.println("Command room: " + config.commandRoomId);
        System.out.println("Export room: " + config.exportRoomId);

        // On startup, check for rooms where the bot should leave (DMs where user already left)
        try {
            System.out.println("Checking for abandoned DMs on startup...");
            String joinedRoomsUrl = url + "/_matrix/client/v3/joined_rooms";
            HttpRequest joinedReq = HttpRequest.newBuilder()
                    .uri(URI.create(joinedRoomsUrl))
                    .header("Authorization", "Bearer " + config.accessToken)
                    .GET()
                    .build();
            HttpResponse<String> joinedResp = client.send(joinedReq, HttpResponse.BodyHandlers.ofString());
            if (joinedResp.statusCode() == 200) {
                JsonNode joinedRooms = mapper.readTree(joinedResp.body()).path("joined_rooms");
                if (joinedRooms.isArray()) {
                    for (JsonNode roomIdNode : joinedRooms) {
                        String roomId = roomIdNode.asText();
                        
                        // Skip configured rooms
                        if (roomId.equals(config.commandRoomId) || roomId.equals(config.exportRoomId)) {
                            continue;
                        }
                        
                        // Check if room is a DM (only 2 members including bot)
                        try {
                            // Get room state to check if it's a DM (only 2 members)
                            // Use /members endpoint to get current member list
                            String membersUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/members";
                            HttpRequest membersReq = HttpRequest.newBuilder()
                                    .uri(URI.create(membersUrl))
                                    .header("Authorization", "Bearer " + config.accessToken)
                                    .GET()
                                    .build();
                            HttpResponse<String> membersResp = client.send(membersReq, HttpResponse.BodyHandlers.ofString());
                            if (membersResp.statusCode() == 200) {
                                JsonNode members = mapper.readTree(membersResp.body()).path("chunk");
                                if (members.isArray()) {
                                    int memberCount = 0;
                                    for (JsonNode member : members) {
                                        String membership = member.path("content").path("membership").asText(null);
                                        if ("join".equals(membership) || "invite".equals(membership)) {
                                            memberCount++;
                                        }
                                    }
                                    
                                    // If only 1 member left (the bot itself), leave the room
                                    if (memberCount <= 1) {
                                        System.out.println("Startup: Room " + roomId + " has " + memberCount + " member(s), bot is leaving");
                                        String leaveUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/leave";
                                        Map<String, Object> leavePayload = new java.util.HashMap<>();
                                        String jsonPayload = mapper.writeValueAsString(leavePayload);
                                        
                                        HttpRequest leaveReq = HttpRequest.newBuilder()
                                                .uri(URI.create(leaveUrl))
                                                .header("Authorization", "Bearer " + config.accessToken)
                                                .header("Content-Type", "application/json")
                                                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                                                .build();
                                        
                                        HttpResponse<String> leaveResp = client.send(leaveReq, HttpResponse.BodyHandlers.ofString());
                                        System.out.println("Bot left room " + roomId + " -> " + leaveResp.statusCode());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("Error checking room " + roomId + " on startup: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error checking joined rooms on startup: " + e.getMessage());
        }

        while (true) {
            try {
                String syncUrl = url + "/_matrix/client/v3/sync?timeout=30000" + (since != null ? "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8) : "");
                HttpRequest syncReq = HttpRequest.newBuilder()
                        .uri(URI.create(syncUrl))
                        .header("Authorization", "Bearer " + config.accessToken)
                        .GET()
                        .build();

                HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
                if (syncResp.statusCode() != 200) {
                    System.out.println("/sync returned: " + syncResp.statusCode() + " - " + syncResp.body());
                    Thread.sleep(2000);
                    continue;
                }

                JsonNode root = mapper.readTree(syncResp.body());
                since = root.path("next_batch").asText(since);

                // Handle invited rooms (auto-join DMs)
                JsonNode inviteRooms = root.path("rooms").path("invite");
                Iterator<String> inviteRoomIds = inviteRooms.fieldNames();
                while (inviteRoomIds.hasNext()) {
                    String roomId = inviteRoomIds.next();
                    System.out.println("Invited to room: " + roomId);
                    
                    // Auto-join the room
                    String joinUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/join";
                    Map<String, Object> joinPayload = new java.util.HashMap<>();
                    String jsonPayload = mapper.writeValueAsString(joinPayload);
                    
                    HttpRequest joinReq = HttpRequest.newBuilder()
                            .uri(URI.create(joinUrl))
                            .header("Authorization", "Bearer " + config.accessToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                            .build();
                    
                    HttpResponse<String> joinResp = client.send(joinReq, HttpResponse.BodyHandlers.ofString());
                    if (joinResp.statusCode() == 200) {
                        System.out.println("Successfully joined room: " + roomId);
                        
                        // After joining, check if room is encrypted by trying to get encryption state
                        // This is more reliable than checking before joining
                        boolean roomIsEncrypted = false;
                        try {
                            String stateUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/state/m.room.encryption/";
                            HttpRequest stateReq = HttpRequest.newBuilder()
                                    .uri(URI.create(stateUrl))
                                    .header("Authorization", "Bearer " + config.accessToken)
                                    .GET()
                                    .build();
                            HttpResponse<String> stateResp = client.send(stateReq, HttpResponse.BodyHandlers.ofString());
                            if (stateResp.statusCode() == 200) {
                                JsonNode encryptionState = mapper.readTree(stateResp.body());
                                if (encryptionState.has("algorithm")) {
                                    roomIsEncrypted = true;
                                    System.out.println("Room " + roomId + " is encrypted with algorithm: " + encryptionState.path("algorithm").asText());
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("Error checking room encryption after join: " + e.getMessage());
                        }
                        
                        // If room is encrypted, send a warning message
                        if (roomIsEncrypted) {
                            String warningMessage = "⚠️ **Warning**: This room is end-to-end encrypted. " +
                                "I cannot read encrypted messages, so commands will not work. " +
                                "Please create an unencrypted room with me for the bot to function properly.";
                            
                            String txnId = "m" + Instant.now().toEpochMilli();
                            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
                            String endpoint = url + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + txnId;
                            
                            Map<String, Object> payload = new java.util.HashMap<>();
                            payload.put("msgtype", "m.text");
                            payload.put("body", warningMessage);
                            payload.put("m.mentions", Map.of());
                            String warningJson = mapper.writeValueAsString(payload);
                            
                            HttpRequest warningReq = HttpRequest.newBuilder()
                                    .uri(URI.create(endpoint))
                                    .header("Authorization", "Bearer " + config.accessToken)
                                    .header("Content-Type", "application/json")
                                    .PUT(HttpRequest.BodyPublishers.ofString(warningJson))
                                    .build();
                            
                            HttpResponse<String> warningResp = client.send(warningReq, HttpResponse.BodyHandlers.ofString());
                            System.out.println("Sent encryption warning to " + roomId + " -> " + warningResp.statusCode());
                        }
                    } else {
                        System.out.println("Failed to join room " + roomId + ": " + joinResp.statusCode() + " - " + joinResp.body());
                    }
                }

                // Handle left rooms (user left DM)
                JsonNode leaveRooms = root.path("rooms").path("leave");
                Iterator<String> leaveRoomIds = leaveRooms.fieldNames();
                while (leaveRoomIds.hasNext()) {
                    String roomId = leaveRoomIds.next();
                    System.out.println("User left room: " + roomId);
                    
                    // For DMs, if the user leaves, the bot should leave too
                    // We can't reliably check member count after leaving, so just leave
                    // Skip configured rooms (command room and export room)
                    if (roomId.equals(config.commandRoomId) || roomId.equals(config.exportRoomId)) {
                        System.out.println("Skipping leave for configured room: " + roomId);
                        continue;
                    }
                    
                    try {
                        System.out.println("Bot is leaving room: " + roomId);
                        String leaveUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/leave";
                        Map<String, Object> leavePayload = new java.util.HashMap<>();
                        String jsonPayload = mapper.writeValueAsString(leavePayload);
                        
                        HttpRequest leaveReq = HttpRequest.newBuilder()
                                .uri(URI.create(leaveUrl))
                                .header("Authorization", "Bearer " + config.accessToken)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                                .build();
                        
                        HttpResponse<String> leaveResp = client.send(leaveReq, HttpResponse.BodyHandlers.ofString());
                        System.out.println("Bot left room " + roomId + " -> " + leaveResp.statusCode());
                    } catch (Exception e) {
                        System.out.println("Error leaving room " + roomId + ": " + e.getMessage());
                    }
                }

                JsonNode rooms = root.path("rooms").path("join");
                Iterator<String> roomIds = rooms.fieldNames();
                while (roomIds.hasNext()) {
                    String roomId = roomIds.next();
                    
                    JsonNode roomNode = rooms.path(roomId);
                    JsonNode timelineNode = roomNode.path("timeline");
                    String prevBatch = timelineNode.path("prev_batch").asText(null);
                    JsonNode timeline = timelineNode.path("events");
                    if (timeline.isArray()) {
                        for (JsonNode ev : timeline) {
                            if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
                            String body = ev.path("content").path("body").asText(null);
                            String sender = ev.path("sender").asText(null);
                            if (body == null) continue;
                            String trimmed = body.trim();
                            
                            // Determine response room: use the room where the command was sent
                            // This allows DMs to be responded to in DMs
                            String responseRoomId = roomId;
                            
                            // Process commands from any room (including DMs)
                            if ("!testcommand".equals(trimmed)) {
                                if (userId != null && userId.equals(sender)) continue;
                                System.out.println("Received !testcommand in " + roomId + " from " + sender);
                                sendText(client, mapper, url, config.accessToken, responseRoomId, "Hello, world!");
                            } else if (trimmed.matches("!export\\d+h")) {
                                if (userId != null && userId.equals(sender)) continue;
                                int hours = Integer.parseInt(trimmed.replaceAll("\\D+", ""));
                                System.out.println("Received export command in " + roomId + " from " + sender + " (" + hours + "h)");
                                // run export in a new thread so we don't block the sync loop
                                final String finalPrevBatch = prevBatch;
                                final Config finalConfig = config;
                                final String finalResponseRoomId = responseRoomId; // Use response room for replies
                                new Thread(() -> exportRoomHistory(client, mapper, url, finalConfig.accessToken, finalResponseRoomId, finalConfig.exportRoomId, hours, finalPrevBatch)).start();
                            } else if (trimmed.matches("!arliai\\s+[A-Z]{3}\\s+\\d+h(?:\\s+(.*))?")) {
                                if (userId != null && userId.equals(sender)) continue;

                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("!arliai\\s+([A-Z]{3})\\s+(\\d+)h(?:\\s+(.*))?").matcher(trimmed);
                                if (matcher.matches()) {
                                    String timezoneAbbr = matcher.group(1);
                                    int hours = Integer.parseInt(matcher.group(2));
                                    String question = matcher.group(3) != null ? matcher.group(3).trim() : null;

                                    System.out.println("Received arliai command in " + roomId + " from " + sender + " (" + timezoneAbbr + ", " + hours + "h" + (question != null ? ", question: " + question : "") + ")");
                                    final int finalHours = hours;
                                    final String finalQuestion = question;
                                    final String finalPrevBatch = prevBatch; // Make prevBatch final for lambda
                                    final Config finalConfig = config;
                                    final String finalResponseRoomId = responseRoomId; // Use response room for replies
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> queryArliAIWithChatLogs(client, mapper, url, finalConfig.accessToken, finalResponseRoomId, finalConfig.exportRoomId, finalHours, finalPrevBatch, finalQuestion, finalConfig, -1, finalTimezoneAbbr)).start();
                                }
                            } else if (trimmed.matches("!arliai-ts\\s+\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\s+[A-Z]{3}\\s+\\d+h(?:\\s+(.*))?")) {
                                if (userId != null && userId.equals(sender)) continue;

                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("!arliai-ts\\s+(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})\\s+([A-Z]{3})\\s+(\\d+)h(?:\\s+(.*))?").matcher(trimmed);
                                if (matcher.matches()) {
                                    String startDateStr = matcher.group(1);
                                    String timezoneAbbr = matcher.group(2);
                                    int durationHours = Integer.parseInt(matcher.group(3));
                                    String question = matcher.group(4) != null ? matcher.group(4).trim() : null;

                                    // Convert YYYY-MM-DD-HH-MM to Unix timestamp (milliseconds) in UTC
                                    // First parse as user's local time, then convert to UTC
                                    ZoneId userZone = getZoneIdFromAbbr(timezoneAbbr);
                                    long startTimestamp = java.time.LocalDateTime.parse(startDateStr, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
                                            .atZone(userZone) // Interpret in user's timezone
                                            .withZoneSameInstant(ZoneId.of("UTC")) // Convert to UTC for API
                                            .toInstant()
                                            .toEpochMilli();

                                    System.out.println("Received arliai-ts command in " + roomId + " from " + sender + " (start=" + startDateStr + " " + timezoneAbbr + ", duration=" + durationHours + "h" + (question != null ? ", question: " + question : "") + ")");
                                    final long finalStartTimestamp = startTimestamp;
                                    final int finalDurationHours = durationHours;
                                    final String finalQuestion = question;
                                    final String finalPrevBatch = prevBatch;
                                    final Config finalConfig = config;
                                    final String finalResponseRoomId = responseRoomId;
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> queryArliAIWithChatLogs(client, mapper, url, finalConfig.accessToken, finalResponseRoomId, finalConfig.exportRoomId, finalDurationHours, finalPrevBatch, finalQuestion, finalConfig, finalStartTimestamp, finalTimezoneAbbr)).start();
                                }
                            } else if (trimmed.matches("!cerebras\\s+[A-Z]{3}\\s+\\d+h(?:\\s+(.*))?")) {
                                if (userId != null && userId.equals(sender)) continue;

                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("!cerebras\\s+([A-Z]{3})\\s+(\\d+)h(?:\\s+(.*))?").matcher(trimmed);
                                if (matcher.matches()) {
                                    String timezoneAbbr = matcher.group(1);
                                    int hours = Integer.parseInt(matcher.group(2));
                                    String question = matcher.group(3) != null ? matcher.group(3).trim() : null;

                                    System.out.println("Received cerebras command in " + roomId + " from " + sender + " (" + timezoneAbbr + ", " + hours + "h" + (question != null ? ", question: " + question : "") + ")");
                                    final int finalHours = hours;
                                    final String finalQuestion = question;
                                    final String finalPrevBatch = prevBatch;
                                    final Config finalConfig = config;
                                    final String finalResponseRoomId = responseRoomId;
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> queryCerebrasAIWithChatLogs(client, mapper, url, finalConfig.accessToken, finalResponseRoomId, finalConfig.exportRoomId, finalHours, finalPrevBatch, finalQuestion, finalConfig, -1, finalTimezoneAbbr)).start();
                                }
                            } else if (trimmed.matches("!cerebras-ts\\s+\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\s+[A-Z]{3}\\s+\\d+h(?:\\s+(.*))?")) {
                                if (userId != null && userId.equals(sender)) continue;

                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("!cerebras-ts\\s+(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})\\s+([A-Z]{3})\\s+(\\d+)h(?:\\s+(.*))?").matcher(trimmed);
                                if (matcher.matches()) {
                                    String startDateStr = matcher.group(1);
                                    String timezoneAbbr = matcher.group(2);
                                    int durationHours = Integer.parseInt(matcher.group(3));
                                    String question = matcher.group(4) != null ? matcher.group(4).trim() : null;

                                    ZoneId userZone = getZoneIdFromAbbr(timezoneAbbr);
                                    long startTimestamp = java.time.LocalDateTime.parse(startDateStr, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
                                            .atZone(userZone)
                                            .withZoneSameInstant(ZoneId.of("UTC"))
                                            .toInstant()
                                            .toEpochMilli();

                                    System.out.println("Received cerebras-ts command in " + roomId + " from " + sender + " (start=" + startDateStr + " " + timezoneAbbr + ", duration=" + durationHours + "h" + (question != null ? ", question: " + question : "") + ")");
                                    final long finalStartTimestamp = startTimestamp;
                                    final int finalDurationHours = durationHours;
                                    final String finalQuestion = question;
                                    final String finalPrevBatch = prevBatch;
                                    final Config finalConfig = config;
                                    final String finalResponseRoomId = responseRoomId;
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> queryCerebrasAIWithChatLogs(client, mapper, url, finalConfig.accessToken, finalResponseRoomId, finalConfig.exportRoomId, finalDurationHours, finalPrevBatch, finalQuestion, finalConfig, finalStartTimestamp, finalTimezoneAbbr)).start();
                                }
                            } else if (trimmed.matches("!semantic\\s+[A-Z]{3}\\s+\\d+h\\s+(.+)")) {
                                if (userId != null && userId.equals(sender)) continue;

                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("!semantic\\s+([A-Z]{3})\\s+(\\d+)h\\s+(.+)").matcher(trimmed);
                                if (matcher.matches()) {
                                    String timezoneAbbr = matcher.group(1);
                                    int hours = Integer.parseInt(matcher.group(2));
                                    String query = matcher.group(3).trim();

                                    System.out.println("Received semantic search command in " + roomId + " from " + sender + " (" + timezoneAbbr + ", " + hours + "h, query: " + query + ")");
                                    final int finalHours = hours;
                                    final String finalQuery = query;
                                    final String finalPrevBatch = prevBatch;
                                    final Config finalConfig = config;
                                    final String finalResponseRoomId = responseRoomId;
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> performSemanticSearch(client, mapper, url, finalConfig.accessToken, finalResponseRoomId, finalConfig.exportRoomId, finalHours, finalPrevBatch, finalQuery, finalTimezoneAbbr)).start();
                                }
                            } else if (trimmed.matches("!grep\\s+[A-Z]{3}\\s+\\d+[dh]\\s+(.+)")) {
                                if (userId != null && userId.equals(sender)) continue;

                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("!grep\\s+([A-Z]{3})\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
                                if (matcher.matches()) {
                                    String timezoneAbbr = matcher.group(1);
                                    int duration = Integer.parseInt(matcher.group(2));
                                    String unit = matcher.group(3);
                                    String pattern = matcher.group(4).trim();
                                    
                                    // Convert to hours
                                    int hours = unit.equals("d") ? duration * 24 : duration;
                                    String durationStr = duration + unit;

                                    System.out.println("Received grep command in " + roomId + " from " + sender + " (" + timezoneAbbr + ", " + durationStr + ", pattern: " + pattern + ")");
                                    final int finalHours = hours;
                                    final String finalPattern = pattern;
                                    final String finalPrevBatch = prevBatch;
                                    final Config finalConfig = config;
                                    final String finalResponseRoomId = responseRoomId;
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> performGrep(client, mapper, url, finalConfig.accessToken, finalResponseRoomId, finalConfig.exportRoomId, finalHours, finalPrevBatch, finalPattern, finalTimezoneAbbr, sender)).start();
                                }
                            } else if (trimmed.matches("!grep-slow\\s+[A-Z]{3}\\s+\\d+[dh]\\s+(.+)")) {
                                if (userId != null && userId.equals(sender)) continue;

                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("!grep-slow\\s+([A-Z]{3})\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
                                if (matcher.matches()) {
                                    String timezoneAbbr = matcher.group(1);
                                    int duration = Integer.parseInt(matcher.group(2));
                                    String unit = matcher.group(3);
                                    String pattern = matcher.group(4).trim();
                                    
                                    // Convert to hours
                                    int hours = unit.equals("d") ? duration * 24 : duration;
                                    String durationStr = duration + unit;

                                    System.out.println("Received grep-slow command in " + roomId + " from " + sender + " (" + timezoneAbbr + ", " + durationStr + ", pattern: " + pattern + ")");
                                    final int finalHours = hours;
                                    final String finalPattern = pattern;
                                    final String finalPrevBatch = prevBatch;
                                    final Config finalConfig = config;
                                    final String finalResponseRoomId = responseRoomId;
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> performGrepSlow(client, mapper, url, finalConfig.accessToken, finalResponseRoomId, finalConfig.exportRoomId, finalHours, finalPrevBatch, finalPattern, finalTimezoneAbbr, sender)).start();
                                }
                            } else if (trimmed.matches("!search\\s+[A-Z]{3}\\s+\\d+[dh]\\s+(.+)")) {
                                if (userId != null && userId.equals(sender)) continue;

                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("!search\\s+([A-Z]{3})\\s+(\\d+)([dh])\\s+(.+)").matcher(trimmed);
                                if (matcher.matches()) {
                                    String timezoneAbbr = matcher.group(1);
                                    int duration = Integer.parseInt(matcher.group(2));
                                    String unit = matcher.group(3);
                                    String query = matcher.group(4).trim();
                                    
                                    // Convert to hours
                                    int hours = unit.equals("d") ? duration * 24 : duration;
                                    String durationStr = duration + unit;

                                    System.out.println("Received search command in " + roomId + " from " + sender + " (" + timezoneAbbr + ", " + durationStr + ", query: " + query + ")");
                                    final int finalHours = hours;
                                    final String finalQuery = query;
                                    final String finalPrevBatch = prevBatch;
                                    final Config finalConfig = config;
                                    final String finalResponseRoomId = responseRoomId;
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> performSearch(client, mapper, url, finalConfig.accessToken, finalResponseRoomId, finalConfig.exportRoomId, finalHours, finalPrevBatch, finalQuery, finalTimezoneAbbr, sender)).start();
                                }
                            } else if ("!abort".equals(trimmed)) {
                                if (userId != null && userId.equals(sender)) continue;
                                System.out.println("Received abort command in " + roomId + " from " + sender);
                                
                                // Check if user has any running operations
                                AtomicBoolean abortFlag = runningOperations.get(sender);
                                if (abortFlag != null) {
                                    abortFlag.set(true);
                                    sendText(client, mapper, url, config.accessToken, responseRoomId, "Aborting your running search/grep operations...");
                                } else {
                                    sendText(client, mapper, url, config.accessToken, responseRoomId, "No running operations found to abort.");
                                }
                            } else if ("!last".equals(trimmed)) {
                                if (userId != null && userId.equals(sender)) continue;
                                System.out.println("Received last command in " + roomId + " from " + sender);
                                final String finalSender = sender;
                                final String finalResponseRoomId = responseRoomId;
                                final Config finalConfig = config;
                                new Thread(() -> sendLastMessageAndReadReceipt(client, mapper, url, finalConfig.accessToken, finalConfig.exportRoomId, finalSender, finalResponseRoomId)).start();
                            } else if ("!ping".equals(trimmed)) {
                                if (userId != null && userId.equals(sender)) continue;
                                System.out.println("Received ping command in " + roomId + " from " + sender);
                                
                                // Get the timestamp from the message event
                                long messageTimestamp = ev.path("origin_server_ts").asLong(Instant.now().toEpochMilli());
                                long currentTime = Instant.now().toEpochMilli();
                                long latencyMs = currentTime - messageTimestamp;
                                
                                String response = "Pong! (ping took " + latencyMs + " ms to arrive)";
                                sendText(client, mapper, url, config.accessToken, responseRoomId, response);
                            } else if ("!help".equals(trimmed)) {
                                if (userId != null && userId.equals(sender)) continue;
                                System.out.println("Received help command in " + roomId + " from " + sender);
                                String helpText = "**Matrix Bot Commands**\n\n" +
                                    "**!ping** - Measure and report ping latency\n\n" +
                                    "**!testcommand** - Test if the bot is responding\n\n" +
                                    "**!export<duration>h** - Export chat history (e.g., `!export24h`)\n" +
                                    "  - Duration: Number of hours to export\n\n" +
                                    "**!last** - Print links to your last message and last read message in the export room\n" +
                                    "  - Shows your most recent message sent in the export room\n" +
                                    "  - Shows your last read message in the export room (if not caught up, shows the difference)\n\n" +
                                    "**!arliai <timezone> <duration>h [question]** - Query Arli AI with chat logs\n" +
                                    "  - Timezone: PST, PDT, MST, MDT, CST, CDT, EST, EDT, UTC, GMT\n" +
                                    "  - Duration: Number of hours of chat history\n" +
                                    "  - Question: Optional question about the logs\n\n" +
                                    "**!arliai-ts <timestamp> <timezone> <duration>h [question]** - Query Arli AI from specific timestamp\n" +
                                    "  - Timestamp: YYYY-MM-DD-HH-MM format\n" +
                                    "  - Timezone: As above\n" +
                                    "  - Duration: Number of hours from timestamp\n\n" +
                                    "**!cerebras <timezone> <duration>h [question]** - Query Cerebras AI with chat logs\n" +
                                    "  - Same format as !arliai\n\n" +
                                    "**!cerebras-ts <timestamp> <timezone> <duration>h [question]** - Query Cerebras AI from specific timestamp\n" +
                                    "  - Same format as !arliai-ts\n\n" +
                                    "**!semantic <timezone> <duration>h <query>** - AI-free semantic search using local embeddings\n" +
                                    "  - Timezone: PST, PDT, MST, MDT, CST, CDT, EST, EDT, UTC, GMT\n" +
                                    "  - Duration: Number of hours to search\n" +
                                    "  - Query: Search terms to find relevant messages\n" +
                                    "  - Returns: Top 5 most relevant messages with similarity scores\n\n" +
                                    "**!grep <timezone> <duration>[h|d] <pattern>** - Literal case-insensitive grep search (fast, stops at 50 results)\n" +
                                    "  - Timezone: PST, PDT, MST, MDT, CST, CDT, EST, EDT, UTC, GMT\n" +
                                    "  - Duration: Number of hours (24h) or days (2d) to search\n" +
                                    "  - Pattern: Literal text to search for (case-insensitive)\n" +
                                    "  - Returns: Up to 50 matching messages with timestamps\n\n" +
                                    "**!grep-slow <timezone> <duration>[h|d] <pattern>** - Literal case-insensitive grep after collecting all messages\n" +
                                    "  - Same format as !grep but collects all messages first, then searches\n" +
                                    "  - Slower but ensures no results are missed\n\n" +
                                    "**!search <timezone> <duration>[h|d] <terms>** - Find messages containing ALL search terms (any order)\n" +
                                    "  - Timezone: PST, PDT, MST, MDT, CST, CDT, EST, EDT, UTC, GMT\n" +
                                    "  - Duration: Number of hours (24h) or days (2d) to search\n" +
                                    "  - Terms: Space-separated literal terms (case-insensitive)\n" +
                                    "  - Example: `!search PST 24h fluffychat robomwm` finds messages with both terms\n\n" +
                                    "**!abort** - Abort your currently running search/grep operations\n\n" +
                                    "**!help** - Show this help message";
                                sendMarkdown(client, mapper, url, config.accessToken, responseRoomId, helpText);
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.out.println("Error during sync loop: " + e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
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
    
    private static String getArliApiKey() {
        // This method can be extended to read from different sources if needed
        // For now, it will be handled through the config file
        return null; // Config is passed directly
    }

    private static void sendText(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, String message) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = url + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + txnId;
            
            // Sanitize message to prevent user pings
            String sanitizedMessage = sanitizeUserIds(message);
            
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("msgtype", "m.text");
            payload.put("body", sanitizedMessage);
            payload.put("m.mentions", Map.of()); // Add empty mentions object to prevent accidental mentions
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Sent reply to " + roomId + " -> " + resp.statusCode());
        } catch (Exception e) {
            System.out.println("Failed to send message: " + e.getMessage());
        }
    }

    private static String sendTextWithEventId(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, String message) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = url + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + txnId;
            
            // Sanitize message to prevent user pings
            String sanitizedMessage = sanitizeUserIds(message);
            
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("msgtype", "m.text");
            payload.put("body", sanitizedMessage);
            payload.put("m.mentions", Map.of());
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                return root.path("event_id").asText(null);
            }
            System.out.println("Sent message to " + roomId + " -> " + resp.statusCode());
            return null;
        } catch (Exception e) {
            System.out.println("Failed to send message: " + e.getMessage());
            return null;
        }
    }

    private static String updateTextMessage(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, String originalEventId, String message) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = url + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + txnId;
            
            // Sanitize message to prevent user pings
            String sanitizedMessage = sanitizeUserIds(message);
            
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("msgtype", "m.text");
            payload.put("body", "* " + sanitizedMessage); // Asterisk prefix for edited messages
            payload.put("m.mentions", Map.of());
            
            // Add new_content field
            Map<String, Object> newContent = new java.util.HashMap<>();
            newContent.put("msgtype", "m.text");
            newContent.put("body", sanitizedMessage);
            newContent.put("m.mentions", Map.of());
            payload.put("m.new_content", newContent);
            
            // Add relation to replace the ORIGINAL message (not the previous update)
            Map<String, Object> relatesTo = new java.util.HashMap<>();
            relatesTo.put("event_id", originalEventId);
            relatesTo.put("rel_type", "m.replace");
            payload.put("m.relates_to", relatesTo);
            
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Updated message " + originalEventId + " -> " + resp.statusCode());
            
            // Return the new event ID for future updates
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                return root.path("event_id").asText(null);
            }
        } catch (Exception e) {
            System.out.println("Failed to update message: " + e.getMessage());
        }
        return null;
    }

    private static void sendMarkdown(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, String message) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = url + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + txnId;
            
            // Sanitize message to prevent user pings by wrapping user IDs in code blocks
            String sanitizedMessage = sanitizeUserIds(message);
            
            // Convert markdown to HTML for Matrix
            String htmlBody = convertMarkdownToHtml(sanitizedMessage);
            
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("msgtype", "m.text");
            payload.put("body", sanitizedMessage); // Plain text fallback
            payload.put("format", "org.matrix.custom.html");
            payload.put("formatted_body", htmlBody); // HTML with markdown formatting
            payload.put("m.mentions", Map.of()); // Add empty mentions object to prevent accidental mentions
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Sent markdown reply to " + roomId + " -> " + resp.statusCode());
        } catch (Exception e) {
            System.out.println("Failed to send markdown message: " + e.getMessage());
        }
    }

    private static String convertMarkdownToHtml(String markdown) {
        // Configure parser and renderer
        Parser parser = Parser.builder().build();
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        
        // Parse and render markdown to HTML
        org.commonmark.node.Node document = parser.parse(markdown);
        String html = renderer.render(document);
        
        // Clean up any extra newlines that might be added by the renderer
        //html = html.replaceAll("\n", "");
        
        return html;
    }

    private static String sanitizeUserIds(String message) {
        // Replace user IDs like @username:domain with `@username:domain` to prevent pings
        // This regex matches Matrix user IDs: @localpart:domain that are NOT already in backticks
        // Pattern: @ followed by alphanumeric/underscore/dot/equals/dash, then :, then domain
        // The backticks prevent the user ID from being interpreted as a mention
        // (?<!`) - not preceded by backtick
        // (?<!`<) - not preceded by backtick+< (handles AI returning `<@user:domain>`)
        // (?!`) - not followed by backtick
        return message.replaceAll("(?<!`)(?<!`<)(@[a-zA-Z0-9._=-]+:[a-zA-Z0-9.-]+)(?!`)", "`$1`");
    }

    private static void exportRoomHistory(HttpClient client, ObjectMapper mapper, String url, String accessToken, String responseRoomId, String exportRoomId, int hours, String fromToken) {
        try {
            long now = System.currentTimeMillis();
            String safeRoom = exportRoomId.replaceAll("[^A-Za-z0-9._-]", "_");
            String filename = safeRoom + "-last" + hours + "h-" + now + ".txt";

            sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Starting export of last " + hours + "h from " + exportRoomId + " to " + filename);

            java.util.List<String> lines = fetchRoomHistory(client, mapper, url, accessToken, exportRoomId, hours, fromToken);

            if (lines.isEmpty()) {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "No chat logs found for the last " + hours + "h to export from " + exportRoomId + ".");
                return;
            }

            try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(filename))) {
                for (String l : lines) w.write(l + "\n");
            }

            sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Export complete: " + filename + " (" + lines.size() + " messages)");
            System.out.println("Exported " + lines.size() + " messages to " + filename);
        } catch (Exception e) {
            System.out.println("Export failed: " + e.getMessage());
            try { sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Export failed: " + e.getMessage()); } catch (Exception ignore) {}
        }
    }

    private static class ChatLogsResult {
        public java.util.List<String> logs;
        public String firstEventId;
        
        ChatLogsResult(java.util.List<String> logs, String firstEventId) {
            this.logs = logs;
            this.firstEventId = firstEventId;
        }
    }

    private static class ChatLogsWithIds {
        public java.util.List<String> logs;
        public java.util.List<String> eventIds;
        
        ChatLogsWithIds(java.util.List<String> logs, java.util.List<String> eventIds) {
            this.logs = logs;
            this.eventIds = eventIds;
        }
    }

    private static void queryArliAIWithChatLogs(HttpClient client, ObjectMapper mapper, String url, String accessToken, String responseRoomId, String exportRoomId, int hours, String fromToken, String question, Config config, long startTimestamp, String timezoneAbbr) {
        try {
            ZoneId zoneId = getZoneIdFromAbbr(timezoneAbbr);
            String timeInfo = "";
            if (startTimestamp > 0) {
                // Convert UTC timestamp to user's timezone for display
                String dateStr = java.time.Instant.ofEpochMilli(startTimestamp)
                        .atZone(zoneId)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                timeInfo = "starting at " + dateStr + " (next " + hours + "h)";
            } else {
                timeInfo = "last " + (hours > 0 ? hours + "h" : "all history");
            }
            sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Querying Arli AI with chat logs from " + exportRoomId + " (" + timeInfo + (question != null ? " and question: " + question : "") + ")...");

            // Calculate the UTC time range for filtering
            long endTime = startTimestamp > 0 ? startTimestamp + (long) hours * 3600L * 1000L : -1;
            
            ChatLogsResult result = fetchRoomHistory(client, mapper, url, accessToken, exportRoomId, hours, fromToken, startTimestamp, endTime, zoneId);
            if (result.logs.isEmpty()) {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            String prompt = buildPrompt(question, result.logs);

            // Make HTTP POST request to Arli AI API
            String arliApiUrl = "https://api.arliai.com";
            String arliApiKey = config.arliApiKey;

            if (arliApiKey == null || arliApiKey.isEmpty()) {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "ARLI_API_KEY is not configured.");
                return;
            }

            java.util.List<Map<String, String>> messages = buildMessages(prompt);

            Map<String, Object> arliPayload = Map.of(
                "model", "Gemma-3-27B-it", // Using a suitable Arli AI model
                "messages", messages,
                "stream", false
            );
            String jsonPayload = mapper.writeValueAsString(arliPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(arliApiUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + arliApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode arliResponse = mapper.readTree(response.body());
                String arliAnswer = arliResponse.path("choices").get(0).path("message").path("content").asText("No response from Arli AI.");
                
                // Append link to the first message if available
                arliAnswer = appendMessageLink(arliAnswer, exportRoomId, result.firstEventId);
                
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, arliAnswer);
            } else {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Failed to get response from Arli AI. Status: " + response.statusCode() + ", Body: " + response.body());
            }

        } catch (Exception e) {
            System.out.println("Failed to query Arli AI with chat logs: " + e.getMessage());
            sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Error querying Arli AI: " + e.getMessage());
        }
    }

    private static void queryCerebrasAIWithChatLogs(HttpClient client, ObjectMapper mapper, String url, String accessToken, String responseRoomId, String exportRoomId, int hours, String fromToken, String question, Config config, long startTimestamp, String timezoneAbbr) {
        try {
            ZoneId zoneId = getZoneIdFromAbbr(timezoneAbbr);
            String timeInfo = "";
            if (startTimestamp > 0) {
                // Convert UTC timestamp to user's timezone for display
                String dateStr = java.time.Instant.ofEpochMilli(startTimestamp)
                        .atZone(zoneId)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                timeInfo = "starting at " + dateStr + " (next " + hours + "h)";
            } else {
                timeInfo = "last " + (hours > 0 ? hours + "h" : "all history");
            }
            sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Querying Cerebras AI with chat logs from " + exportRoomId + " (" + timeInfo + (question != null ? " and question: " + question : "") + ")...");

            // Calculate the UTC time range for filtering
            long endTime = startTimestamp > 0 ? startTimestamp + (long) hours * 3600L * 1000L : -1;
            
            ChatLogsResult result = fetchRoomHistory(client, mapper, url, accessToken, exportRoomId, hours, fromToken, startTimestamp, endTime, zoneId);
            if (result.logs.isEmpty()) {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            String prompt = buildPrompt(question, result.logs);

            // Make HTTP POST request to Cerebras AI API
            String cerebrasApiUrl = "https://api.cerebras.ai";
            String cerebrasApiKey = config.cerebrasApiKey;

            if (cerebrasApiKey == null || cerebrasApiKey.isEmpty()) {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "CEREBRAS_API_KEY is not configured.");
                return;
            }

            java.util.List<Map<String, String>> messages = buildMessages(prompt);

            Map<String, Object> cerebrasPayload = Map.of(
                "model", "gpt-oss-120b",
                "messages", messages,
                "stream", false
            );
            String jsonPayload = mapper.writeValueAsString(cerebrasPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cerebrasApiUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cerebrasApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode cerebrasResponse = mapper.readTree(response.body());
                String cerebrasAnswer = cerebrasResponse.path("choices").get(0).path("message").path("content").asText("No response from Cerebras AI.");
                
                // Append link to the first message if available
                cerebrasAnswer = appendMessageLink(cerebrasAnswer, exportRoomId, result.firstEventId);
                
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, cerebrasAnswer);
            } else {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Failed to get response from Cerebras AI. Status: " + response.statusCode() + ", Body: " + response.body());
            }

        } catch (Exception e) {
            System.out.println("Failed to query Cerebras AI with chat logs: " + e.getMessage());
            sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Error querying Cerebras AI: " + e.getMessage());
        }
    }

    private static java.util.List<String> fetchRoomHistory(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, int hours, String fromToken) {
        ChatLogsResult result = fetchRoomHistory(client, mapper, url, accessToken, roomId, hours, fromToken, -1, -1, ZoneId.of("America/Los_Angeles"));
        return result.logs;
    }

    private static ChatLogsWithIds fetchRoomHistoryWithIds(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, int hours, String fromToken, long startTimestamp, long endTime, ZoneId zoneId) {
        java.util.List<String> logs = new java.util.ArrayList<>();
        java.util.List<String> eventIds = new java.util.ArrayList<>();
        
        // Calculate the time range
        long startTime = (startTimestamp > 0) ? startTimestamp : System.currentTimeMillis() - (long) hours * 3600L * 1000L;
        long calculatedEndTime = (endTime > 0) ? endTime : System.currentTimeMillis();
        
        // If we don't have a pagination token, try to get one via a short sync
        if (fromToken == null) {
            try {
                HttpRequest syncReq = HttpRequest.newBuilder()
                        .uri(URI.create(url + "/_matrix/client/v3/sync?timeout=0"))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
                if (syncResp.statusCode() == 200) {
                    JsonNode root = mapper.readTree(syncResp.body());
                    JsonNode roomNode = root.path("rooms").path("join").path(roomId);
                    if (!roomNode.isMissingNode()) {
                         fromToken = roomNode.path("timeline").path("prev_batch").asText(null);
                    }
                }
            } catch (Exception ignore) {
                // ignore errors here, we'll just start fetching from the latest available if sync fails
            }
        }

        String token = fromToken;

        while (token != null) {
            try {
                String messagesUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                HttpRequest msgReq = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> msgResp = client.send(msgReq, HttpResponse.BodyHandlers.ofString());
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
                        continue; // Skip messages newer than our range
                    }

                    // Stop if we've gone past our time range
                    if (originServerTs < startTime) {
                        reachedStart = true;
                        break; // Stop when we reach messages older than start time
                    }
                    
                    String body = ev.path("content").path("body").asText(null);
                    String sender = ev.path("sender").asText(null);
                    String eventId = ev.path("event_id").asText(null);
                    if (body != null && sender != null && eventId != null) {
                        // Format timestamp with timezone (convert UTC to user's timezone)
                        String timestamp = java.time.Instant.ofEpochMilli(originServerTs)
                                .atZone(zoneId)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                        logs.add("[" + timestamp + "] <" + sender + "> " + body);
                        eventIds.add(eventId);
                    }
                }
                
                if (reachedStart) {
                    break; // We've collected all messages in our time range
                }
                
                if (token != null) {
                     token = root.path("end").asText(null);
                }

            } catch (Exception e) {
                System.out.println("Error fetching room history: " + e.getMessage());
                break;
            }
        }
        java.util.Collections.reverse(logs);
        java.util.Collections.reverse(eventIds);
        return new ChatLogsWithIds(logs, eventIds);
    }

    private static ChatLogsResult fetchRoomHistory(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, int hours, String fromToken, long startTimestamp, long endTime, ZoneId zoneId) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String firstEventId = null;
        
        // Calculate the time range
        // If startTimestamp is provided (arliai-ts), use it as start and add duration for end
        // If startTimestamp is -1 (arliai), use current time minus duration as start, and current time as end
        long startTime = (startTimestamp > 0) ? startTimestamp : System.currentTimeMillis() - (long) hours * 3600L * 1000L;
        long calculatedEndTime = (endTime > 0) ? endTime : System.currentTimeMillis();
        
        // If we don't have a pagination token, try to get one via a short sync
        if (fromToken == null) {
            try {
                HttpRequest syncReq = HttpRequest.newBuilder()
                        .uri(URI.create(url + "/_matrix/client/v3/sync?timeout=0"))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
                if (syncResp.statusCode() == 200) {
                    JsonNode root = mapper.readTree(syncResp.body());
                    JsonNode roomNode = root.path("rooms").path("join").path(roomId);
                    if (!roomNode.isMissingNode()) {
                         fromToken = roomNode.path("timeline").path("prev_batch").asText(null);
                    }
                }
            } catch (Exception ignore) {
                // ignore errors here, we'll just start fetching from the latest available if sync fails
            }
        }

        String token = fromToken;

        while (token != null) {
            try {
                String messagesUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                HttpRequest msgReq = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> msgResp = client.send(msgReq, HttpResponse.BodyHandlers.ofString());
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
                    
                    // Stop if we've gone past our time range
                    if (originServerTs > calculatedEndTime) {
                        continue; // Skip messages newer than our range
                    }
                    if (originServerTs < startTime) {
                        reachedStart = true;
                        break; // Stop when we reach messages older than start time
                    }
                    
                    String body = ev.path("content").path("body").asText(null);
                    String sender = ev.path("sender").asText(null);
                    String eventId = ev.path("event_id").asText(null);
                    if (body != null && sender != null) {
                        // Format timestamp with timezone (convert UTC to user's timezone)
                        String timestamp = java.time.Instant.ofEpochMilli(originServerTs)
                                .atZone(zoneId)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                        lines.add("[" + timestamp + "] <" + sender + "> " + body);
                        
                        // Track the event ID of the oldest message in the range
                        // Since we're iterating newest to oldest, keep updating until we finish
                        firstEventId = eventId;
                    }
                }
                
                if (reachedStart) {
                    break; // We've collected all messages in our time range
                }
                
                if (token != null) {
                     token = root.path("end").asText(null);
                }

            } catch (Exception e) {
                System.out.println("Error fetching room history: " + e.getMessage());
                break;
            }
        }
        java.util.Collections.reverse(lines);
        return new ChatLogsResult(lines, firstEventId);
    }

    private static ZoneId getZoneIdFromAbbr(String timezoneAbbr) {
        // Map timezone abbreviations to ZoneId
        switch (timezoneAbbr.toUpperCase()) {
            case "PST": return ZoneId.of("America/Los_Angeles");
            case "PDT": return ZoneId.of("America/Los_Angeles");
            case "MST": return ZoneId.of("America/Denver");
            case "MDT": return ZoneId.of("America/Denver");
            case "CST": return ZoneId.of("America/Chicago");
            case "CDT": return ZoneId.of("America/Chicago");
            case "EST": return ZoneId.of("America/New_York");
            case "EDT": return ZoneId.of("America/New_York");
            case "UTC": return ZoneId.of("UTC");
            case "GMT": return ZoneId.of("GMT");
            default: return ZoneId.of("America/Los_Angeles");
        }
    }



    private static void performSemanticSearch(HttpClient client, ObjectMapper mapper, String url, String accessToken, String responseRoomId, String exportRoomId, int hours, String fromToken, String query, String timezoneAbbr) {
        try {
            ZoneId zoneId = getZoneIdFromAbbr(timezoneAbbr);
            String timeInfo = "last " + hours + "h";
            
            sendText(client, mapper, url, accessToken, responseRoomId, "Performing semantic search in " + exportRoomId + " for: \"" + query + "\" (" + timeInfo + ")...");

            // Fetch chat history with event IDs
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();
            ChatLogsWithIds result = fetchRoomHistoryWithIds(client, mapper, url, accessToken, exportRoomId, hours, fromToken, startTime, endTime, zoneId);
            
            if (result.logs.isEmpty()) {
                sendText(client, mapper, url, accessToken, responseRoomId, "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            // Build message list with real event IDs
            java.util.List<SemanticSearchEngine.MessageEmbedding> embeddings = new java.util.ArrayList<>();
            for (int i = 0; i < result.logs.size(); i++) {
                String log = result.logs.get(i);
                String eventId = result.eventIds.get(i);
                // Parse log format: "[timestamp] <sender> message"
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[(.*?)\\] <(.*?)> (.*)");
                java.util.regex.Matcher matcher = pattern.matcher(log);
                if (matcher.matches()) {
                    String timestamp = matcher.group(1);
                    String sender = matcher.group(2);
                    String message = matcher.group(3);
                    
                    embeddings.add(new SemanticSearchEngine.MessageEmbedding(eventId, message, timestamp, sender, new double[0]));
                }
            }

            // Perform semantic search
            java.util.List<SemanticSearchEngine.MessageEmbedding> searchResults = SemanticSearchEngine.search(query, embeddings, 5);
            
            if (searchResults.isEmpty()) {
                sendText(client, mapper, url, accessToken, responseRoomId, "No relevant messages found for query: \"" + query + "\"");
                return;
            }

            // Format results with simple links
            StringBuilder response = new StringBuilder();
            response.append("Semantic Search Results\n\n");
            response.append("Query: \"").append(query).append("\"\n");
            response.append("Time range: last ").append(hours).append(" hours\n\n");
            
            for (SemanticSearchEngine.MessageEmbedding resultMsg : searchResults) {
                double similarity = resultMsg.embedding.length > 0 ? resultMsg.embedding[0] : 0.0;
                
                // Create proper Matrix message link
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + resultMsg.eventId;
                
                response.append("[").append(resultMsg.timestamp).append("] <").append(resultMsg.sender).append("> (score: ").append(String.format("%.2f", similarity)).append(")\n");
                response.append(resultMsg.message).append("\n");
                response.append(messageLink).append("\n\n");
            }

            sendText(client, mapper, url, accessToken, responseRoomId, response.toString());

        } catch (Exception e) {
            System.out.println("Failed to perform semantic search: " + e.getMessage());
            sendText(client, mapper, url, accessToken, responseRoomId, "Error performing semantic search: " + e.getMessage());
        }
    }

    private static void performGrep(HttpClient client, ObjectMapper mapper, String url, String accessToken, String responseRoomId, String exportRoomId, int hours, String fromToken, String pattern, String timezoneAbbr, String sender) {
        try {
            // Register this operation for abort capability
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);
            
            ZoneId zoneId = getZoneIdFromAbbr(timezoneAbbr);
            String timeInfo = "last " + hours + "h";
            
            // Send initial message and get event ID for updates
            String initialMessage = "Performing grep search in " + exportRoomId + " for: \"" + pattern + "\" (" + timeInfo + ")...";
            String eventMessageId = sendTextWithEventId(client, mapper, url, accessToken, responseRoomId, initialMessage);
            String originalEventId = eventMessageId; // Track the original event ID for all updates
            
            // Calculate the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();
            
            // If we don't have a pagination token, try to get one via a short sync
            String token = fromToken;
            if (token == null) {
                try {
                    HttpRequest syncReq = HttpRequest.newBuilder()
                            .uri(URI.create(url + "/_matrix/client/v3/sync?timeout=0"))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
                    if (syncResp.statusCode() == 200) {
                        JsonNode root = mapper.readTree(syncResp.body());
                        JsonNode roomNode = root.path("rooms").path("join").path(exportRoomId);
                        if (!roomNode.isMissingNode()) {
                             token = roomNode.path("timeline").path("prev_batch").asText(null);
                        }
                    }
                } catch (Exception ignore) {
                    // ignore errors here, we'll just start fetching from the latest available if sync fails
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
                    String messagesUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(exportRoomId, StandardCharsets.UTF_8)
                            + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                    HttpRequest msgReq = HttpRequest.newBuilder()
                            .uri(URI.create(messagesUrl))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> msgResp = client.send(msgReq, HttpResponse.BodyHandlers.ofString());
                    if (msgResp.statusCode() != 200) {
                        System.out.println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                        break;
                    }
                    JsonNode root = mapper.readTree(msgResp.body());
                    JsonNode chunk = root.path("chunk");
                    if (!chunk.isArray() || chunk.size() == 0) break;

                    boolean reachedStart = false;
                    for (JsonNode ev : chunk) {
                        // Check for abort signal inside the loop too
                        if (abortFlag.get()) {
                            System.out.println("Grep search aborted by user: " + sender);
                            runningOperations.remove(sender);
                            return;
                        }
                        
                        if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
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
                                if (eventMessageId != null && (results.size() - lastResultCount >= 5 || System.currentTimeMillis() - lastUpdateTime > 2000)) {
                                    StringBuilder updateMsg = new StringBuilder();
                                    updateMsg.append("Grep results for \"").append(pattern).append("\" - ");
                                    updateMsg.append("from last ").append(hours).append(" hours. ");
                                    updateMsg.append(results.size()).append(" matches (searching...)\n");
                                    for (int i = 0; i < results.size(); i++) {
                                        updateMsg.append(results.get(i)).append(" ");
                                        String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + eventIds.get(i);
                                        updateMsg.append(messageLink).append("\n");
                                    }
                                    // Always use original event ID for updates
                                    updateTextMessage(client, mapper, url, accessToken, responseRoomId, originalEventId, updateMsg.toString());
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
                    updateTextMessage(client, mapper, url, accessToken, responseRoomId, originalEventId, "No matches found for pattern: \"" + pattern + "\" in " + timeInfo + " of " + exportRoomId + ".");
                } else {
                    sendText(client, mapper, url, accessToken, responseRoomId, "No matches found for pattern: \"" + pattern + "\" in " + timeInfo + " of " + exportRoomId + ".");
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
                updateTextMessage(client, mapper, url, accessToken, responseRoomId, originalEventId, response.toString());
            } else {
                sendText(client, mapper, url, accessToken, responseRoomId, response.toString());
            }

            runningOperations.remove(sender);

        } catch (Exception e) {
            System.out.println("Failed to perform grep: " + e.getMessage());
            sendText(client, mapper, url, accessToken, responseRoomId, "Error performing grep: " + e.getMessage());
            runningOperations.remove(sender);
        }
    }

    private static void performGrepSlow(HttpClient client, ObjectMapper mapper, String url, String accessToken, String responseRoomId, String exportRoomId, int hours, String fromToken, String pattern, String timezoneAbbr, String sender) {
        try {
            // Register this operation for abort capability
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);
            
            ZoneId zoneId = getZoneIdFromAbbr(timezoneAbbr);
            String timeInfo = "last " + hours + "h";
            
            sendText(client, mapper, url, accessToken, responseRoomId, "Performing slow grep search in " + exportRoomId + " for: \"" + pattern + "\" (" + timeInfo + ")...");

            // Calculate the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();
            
            // Use existing fetchRoomHistoryWithIds to get all messages first
            // Note: This method doesn't support abort during fetch, but we can check after
            ChatLogsWithIds result = fetchRoomHistoryWithIds(client, mapper, url, accessToken, exportRoomId, hours, fromToken, startTime, endTime, zoneId);
            
            // Check for abort after fetch
            if (abortFlag.get()) {
                System.out.println("Grep-slow aborted by user: " + sender);
                runningOperations.remove(sender);
                return;
            }
            
            if (result.logs.isEmpty()) {
                sendText(client, mapper, url, accessToken, responseRoomId, "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
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
                sendText(client, mapper, url, accessToken, responseRoomId, "No matches found for pattern: \"" + pattern + "\" in " + timeInfo + " of " + exportRoomId + ".");
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

            sendText(client, mapper, url, accessToken, responseRoomId, response.toString());
            runningOperations.remove(sender);

        } catch (Exception e) {
            System.out.println("Failed to perform grep-slow: " + e.getMessage());
            sendText(client, mapper, url, accessToken, responseRoomId, "Error performing grep-slow: " + e.getMessage());
            runningOperations.remove(sender);
        }
    }

    private static void performSearch(HttpClient client, ObjectMapper mapper, String url, String accessToken, String responseRoomId, String exportRoomId, int hours, String fromToken, String query, String timezoneAbbr, String sender) {
        try {
            // Register this operation for abort capability
            AtomicBoolean abortFlag = new AtomicBoolean(false);
            runningOperations.put(sender, abortFlag);
            
            ZoneId zoneId = getZoneIdFromAbbr(timezoneAbbr);
            String timeInfo = "last " + hours + "h";
            
            // Send initial message and get event ID for updates
            String initialMessage = "Performing search in " + exportRoomId + " for: \"" + query + "\" (" + timeInfo + ")...";
            String eventMessageId = sendTextWithEventId(client, mapper, url, accessToken, responseRoomId, initialMessage);
            String originalEventId = eventMessageId; // Track the original event ID for all updates
            
            // Calculate the time range
            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();
            
            // If we don't have a pagination token, try to get one via a short sync
            String token = fromToken;
            if (token == null) {
                try {
                    HttpRequest syncReq = HttpRequest.newBuilder()
                            .uri(URI.create(url + "/_matrix/client/v3/sync?timeout=0"))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
                    if (syncResp.statusCode() == 200) {
                        JsonNode root = mapper.readTree(syncResp.body());
                        JsonNode roomNode = root.path("rooms").path("join").path(exportRoomId);
                        if (!roomNode.isMissingNode()) {
                             token = roomNode.path("timeline").path("prev_batch").asText(null);
                        }
                    }
                } catch (Exception ignore) {
                    // ignore errors here, we'll just start fetching from the latest available if sync fails
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
                    String messagesUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(exportRoomId, StandardCharsets.UTF_8)
                            + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                    HttpRequest msgReq = HttpRequest.newBuilder()
                            .uri(URI.create(messagesUrl))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build();
                    HttpResponse<String> msgResp = client.send(msgReq, HttpResponse.BodyHandlers.ofString());
                    if (msgResp.statusCode() != 200) {
                        System.out.println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                        break;
                    }
                    JsonNode root = mapper.readTree(msgResp.body());
                    JsonNode chunk = root.path("chunk");
                    if (!chunk.isArray() || chunk.size() == 0) break;

                    boolean reachedStart = false;
                    for (JsonNode ev : chunk) {
                        // Check for abort signal inside the loop too
                        if (abortFlag.get()) {
                            System.out.println("Search aborted by user: " + sender);
                            runningOperations.remove(sender);
                            return;
                        }
                        
                        if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
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
                                if (eventMessageId != null && (results.size() - lastResultCount >= 5 || System.currentTimeMillis() - lastUpdateTime > 2000)) {
                                    StringBuilder updateMsg = new StringBuilder();
                                    updateMsg.append("Search results for \"").append(query).append("\" - ");
                                    updateMsg.append("from last ").append(hours).append(" hours. ");
                                    updateMsg.append(results.size()).append(" matches (searching...)\n");
                                    for (int i = 0; i < results.size(); i++) {
                                        updateMsg.append(results.get(i)).append(" ");
                                        String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + eventIds.get(i);
                                        updateMsg.append(messageLink).append("\n");
                                    }
                                    // Always use original event ID for updates
                                    updateTextMessage(client, mapper, url, accessToken, responseRoomId, originalEventId, updateMsg.toString());
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
                    updateTextMessage(client, mapper, url, accessToken, responseRoomId, originalEventId, "No messages found containing all terms: \"" + query + "\" in " + timeInfo + " of " + exportRoomId + ".");
                } else {
                    sendText(client, mapper, url, accessToken, responseRoomId, "No messages found containing all terms: \"" + query + "\" in " + timeInfo + " of " + exportRoomId + ".");
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
                updateTextMessage(client, mapper, url, accessToken, responseRoomId, originalEventId, response.toString());
            } else {
                sendText(client, mapper, url, accessToken, responseRoomId, response.toString());
            }

            runningOperations.remove(sender);

        } catch (Exception e) {
            System.out.println("Failed to perform search: " + e.getMessage());
            sendText(client, mapper, url, accessToken, responseRoomId, "Error performing search: " + e.getMessage());
            runningOperations.remove(sender);
        }
    }

    private static void sendLastMessageAndReadReceipt(HttpClient client, ObjectMapper mapper, String url, String accessToken, String exportRoomId, String sender, String responseRoomId) {
        try {
            // Get the last message sent by the sender in the export room
            String lastMessageEventId = getLastMessageFromSender(client, mapper, url, accessToken, exportRoomId, sender);
            
            // Get the last read message for the sender in the export room
            String lastReadEventId = getReadReceipt(client, mapper, url, accessToken, exportRoomId, sender);
            
            StringBuilder response = new StringBuilder();
            
            // Last message sent by sender
            if (lastMessageEventId != null) {
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + lastMessageEventId;
                response.append("sent: ");
                response.append(messageLink).append("\n");
            } else {
                response.append("No recently sent.\n");
            }
            
            // Last read message
            if (lastReadEventId != null) {
                // Check if it's the latest message
                boolean isLatest = isLatestMessage(client, mapper, url, accessToken, exportRoomId, lastReadEventId);
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + lastReadEventId;
                
                if (isLatest) {
                    // User is caught up - show message with link to latest
                    response.append(" no unread. Latest: ");
                    response.append(messageLink).append("\n");
                } else {
                    // User is behind - show only the link to the message they last read
                    response.append(" read: ");
                    response.append(messageLink).append("\n");
                }
            } else {
                response.append("No read receipt found.\n");
            }
            
            sendMarkdown(client, mapper, url, accessToken, responseRoomId, response.toString());
            
        } catch (Exception e) {
            System.out.println("Failed to get last message info: " + e.getMessage());
            sendText(client, mapper, url, accessToken, responseRoomId, "Error getting last message info: " + e.getMessage());
        }
    }

    private static String getLastMessageFromSender(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, String sender) {
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
            JsonNode roomNode = root.path("rooms").path("join").path(roomId);
            if (roomNode.isMissingNode()) {
                return null;
            }
            
            // Get the prev_batch token to fetch history
            String prevBatch = roomNode.path("timeline").path("prev_batch").asText(null);
            if (prevBatch == null) {
                return null;
            }
            
            // First attempt: Fetch recent messages going backwards
            String messagesUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
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
                String messagesUrl2 = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
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

    private static String getReadReceipt(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, String userId) {
        try {
            // Get read receipts for the user in this room
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String encodedUser = URLEncoder.encode(userId, StandardCharsets.UTF_8);
            
            // Collect all receipts with their timestamps
            java.util.Map<Long, java.util.List<String>> receiptsWithTimestamps = new java.util.TreeMap<>(java.util.Collections.reverseOrder());
            
            // First, try to get the read receipt from the sync response
            String syncUrl = url + "/_matrix/client/v3/sync?timeout=0";
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(syncUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
            
            if (syncResp.statusCode() != 200) {
                System.out.println("Failed to sync for read receipt: " + syncResp.statusCode());
                // Continue to account data check
            } else {
                JsonNode root = mapper.readTree(syncResp.body());
                JsonNode roomNode = root.path("rooms").path("join").path(roomId);
                if (!roomNode.isMissingNode()) {
                    // Check ephemeral events for read receipts
                    JsonNode ephemeral = roomNode.path("ephemeral").path("events");
                    if (ephemeral.isArray()) {
                        for (JsonNode ev : ephemeral) {
                            if ("m.receipt".equals(ev.path("type").asText(null))) {
                                JsonNode content = ev.path("content");
                                // content is a map of event_id -> { "m.read": { user_id: timestamp } }
                                Iterator<String> eventIds = content.fieldNames();
                                while (eventIds.hasNext()) {
                                    String eventId = eventIds.next();
                                    JsonNode receiptData = content.path(eventId).path("m.read");
                                    if (receiptData.has(userId)) {
                                        JsonNode timestampNode = receiptData.path(userId);
                                        long timestamp = 0;
                                        
                                        // Check if timestampNode is an object with "ts" field
                                        if (timestampNode.isObject() && timestampNode.has("ts")) {
                                            timestamp = timestampNode.path("ts").asLong(0);
                                        } else {
                                            // Fallback to direct long value
                                            timestamp = timestampNode.asLong(0);
                                        }
                                        
                                        System.out.println("Found receipt for event " + eventId + " with timestamp node: " + timestampNode + " and timestamp: " + timestamp);
                                        
                                        // If timestamp is still 0, use the event_id as a fallback to ensure we can still sort
                                        if (timestamp == 0) {
                                            timestamp = eventId.hashCode();
                                            System.out.println("Using event_id hash as timestamp: " + timestamp);
                                        }
                                        
                                        // Store all event_ids for the same timestamp
                                        receiptsWithTimestamps.computeIfAbsent(timestamp, k -> new java.util.ArrayList<>()).add(eventId);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Always check room account data for the most recent read receipt
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
                    System.out.println("Found last read receipt in account data: " + lastRead);
                    // Add the account data receipt to our map with a high priority timestamp
                    long accountDataTimestamp = Long.MAX_VALUE - 1;
                    receiptsWithTimestamps.computeIfAbsent(accountDataTimestamp, k -> new java.util.ArrayList<>()).add(lastRead);
                }
            }
            
            // Return the most recent receipt (highest timestamp)
            if (!receiptsWithTimestamps.isEmpty()) {
                // Get the first entry in the map (highest timestamp due to reverse order)
                java.util.Map.Entry<Long, java.util.List<String>> firstEntry = receiptsWithTimestamps.entrySet().iterator().next();
                // Return the last event_id in the list (most recent for that timestamp)
                String mostRecentEventId = firstEntry.getValue().get(firstEntry.getValue().size() - 1);
                System.out.println("Returning most recent receipt: " + mostRecentEventId + " with timestamp " + firstEntry.getKey());
                return mostRecentEventId;
            }
            
            System.out.println("No read receipt found for user " + userId);
            return null;
            
        } catch (Exception e) {
            System.out.println("Error getting read receipt: " + e.getMessage());
            return null;
        }
    }

    private static boolean isLatestMessage(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, String eventId) {
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
            JsonNode roomNode = root.path("rooms").path("join").path(roomId);
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

    // Unused for now - kept for potential future use
    // This method fetches the actual message content for a given event ID
    // Currently not used in sendLastMessageAndReadReceipt since we only show links
    private static String getMessageContent(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, String eventId) {
        try {
            // Try to get the message from the sync response first
            String syncUrl = url + "/_matrix/client/v3/sync?timeout=0";
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(syncUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
            
            if (syncResp.statusCode() == 200) {
                JsonNode root = mapper.readTree(syncResp.body());
                JsonNode roomNode = root.path("rooms").path("join").path(roomId);
                if (!roomNode.isMissingNode()) {
                    // Check timeline events
                    JsonNode timeline = roomNode.path("timeline").path("events");
                    if (timeline.isArray()) {
                        for (JsonNode ev : timeline) {
                            if (ev.path("event_id").asText(null).equals(eventId)) {
                                String body = ev.path("content").path("body").asText(null);
                                if (body != null) {
                                    return body;
                                }
                            }
                        }
                    }
                }
            }
            
            // If not found in sync, try to fetch the event directly
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String encodedEvent = URLEncoder.encode(eventId, StandardCharsets.UTF_8);
            String eventUrl = url + "/_matrix/client/v3/rooms/" + encodedRoom + "/event/" + encodedEvent;
            
            HttpRequest eventReq = HttpRequest.newBuilder()
                    .uri(URI.create(eventUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> eventResp = client.send(eventReq, HttpResponse.BodyHandlers.ofString());
            
            if (eventResp.statusCode() == 200) {
                JsonNode event = mapper.readTree(eventResp.body());
                String body = event.path("content").path("body").asText(null);
                return body;
            }
            
            return null;
            
        } catch (Exception e) {
            System.out.println("Error getting message content: " + e.getMessage());
            return null;
        }
    }
}
