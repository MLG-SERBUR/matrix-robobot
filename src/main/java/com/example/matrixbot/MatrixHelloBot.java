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

public class MatrixHelloBot {
    
    private static class Config {
        public String homeserver;
        public String accessToken;
        public String commandRoomId;
        public String exportRoomId;
        public String arliApiKey;
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

                JsonNode rooms = root.path("rooms").path("join");
                Iterator<String> roomIds = rooms.fieldNames();
                while (roomIds.hasNext()) {
                    String roomId = roomIds.next();
                    
                    // Only process messages from the command room
                    if (!roomId.equals(config.commandRoomId)) {
                        continue;
                    }
                    
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
                            
                            // Process commands only from command room
                            if ("!testcommand".equals(trimmed)) {
                                if (userId != null && userId.equals(sender)) continue;
                                System.out.println("Received !testcommand in " + roomId + " from " + sender);
                                sendText(client, mapper, url, config.accessToken, roomId, "Hello, world!");
                            } else if (trimmed.matches("!export\\d+h")) {
                                if (userId != null && userId.equals(sender)) continue;
                                int hours = Integer.parseInt(trimmed.replaceAll("\\D+", ""));
                                System.out.println("Received export command in " + roomId + " from " + sender + " (" + hours + "h)");
                                // run export in a new thread so we don't block the sync loop
                                final String finalPrevBatch = prevBatch;
                                final Config finalConfig = config;
                                final String finalRoomId = roomId; // Command room for responses
                                new Thread(() -> exportRoomHistory(client, mapper, url, finalConfig.accessToken, finalRoomId, finalConfig.exportRoomId, hours, finalPrevBatch)).start();
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
                                    final String finalRoomId = roomId; // Command room for responses
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> queryArliAIWithChatLogs(client, mapper, url, finalConfig.accessToken, finalRoomId, finalConfig.exportRoomId, finalHours, finalPrevBatch, finalQuestion, finalConfig, -1, finalTimezoneAbbr)).start();
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
                                    final String finalRoomId = roomId;
                                    final String finalTimezoneAbbr = timezoneAbbr;
                                    new Thread(() -> queryArliAIWithChatLogs(client, mapper, url, finalConfig.accessToken, finalRoomId, finalConfig.exportRoomId, finalDurationHours, finalPrevBatch, finalQuestion, finalConfig, finalStartTimestamp, finalTimezoneAbbr)).start();
                                }
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
        html = html.replaceAll("\n", "");
        
        return html;
    }

    private static String sanitizeUserIds(String message) {
        // Replace user IDs like @username:domain with `@username:domain` to prevent pings
        // This regex matches Matrix user IDs: @localpart:domain
        return message.replaceAll("(@[a-zA-Z0-9._=-]+:[a-zA-Z0-9.-]+)", "`$1`");
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
            
            java.util.List<String> chatLogs = fetchRoomHistory(client, mapper, url, accessToken, exportRoomId, hours, fromToken, startTimestamp, endTime, zoneId);
            if (chatLogs.isEmpty()) {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            String prompt = "";
            if (question != null && !question.isEmpty()) {
                prompt = "Given the following chat logs, answer the question: '" + question + "'\\n\\n" + String.join("\\n", chatLogs);
            } else {
                prompt = "Give a high level overview of the following chat logs. Use only a title and timestamp for each topic and only include one or more chat messages verbatim (with username) as bullet points for each topic. Then summarize with bullet points all of the chat at end:\\n\\n" + String.join("\\n", chatLogs);
            }

            // Make HTTP POST request to Arli AI API
            String arliApiUrl = "https://api.arliai.com";
            String arliApiKey = config.arliApiKey;

            if (arliApiKey == null || arliApiKey.isEmpty()) {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "ARLI_API_KEY is not configured.");
                return;
            }

            java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
            messages.add(Map.of("role", "system", "content", "You provide high level overview of a chat log."));
            messages.add(Map.of("role", "user", "content", prompt));

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
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, arliAnswer);
            } else {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Failed to get response from Arli AI. Status: " + response.statusCode() + ", Body: " + response.body());
            }

        } catch (Exception e) {
            System.out.println("Failed to query Arli AI with chat logs: " + e.getMessage());
            sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Error querying Arli AI: " + e.getMessage());
        }
    }

    private static java.util.List<String> fetchRoomHistory(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, int hours, String fromToken) {
        return fetchRoomHistory(client, mapper, url, accessToken, roomId, hours, fromToken, -1, -1, ZoneId.of("America/Los_Angeles"));
    }

    private static java.util.List<String> fetchRoomHistory(HttpClient client, ObjectMapper mapper, String url, String accessToken, String roomId, int hours, String fromToken, long startTimestamp, long endTime, ZoneId zoneId) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        
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
        int safety = 0;

        while (token != null && safety < 100) {
            safety++;
            try {
                String messagesUrl = url + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=100";
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
                    if (body != null && sender != null) {
                        // Format timestamp with timezone (convert UTC to user's timezone)
                        String timestamp = java.time.Instant.ofEpochMilli(originServerTs)
                                .atZone(zoneId)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                        lines.add("[" + timestamp + "] <" + sender + "> " + body);
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
        return lines;
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
}
