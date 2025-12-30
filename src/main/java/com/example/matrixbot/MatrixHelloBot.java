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
                            } else if (trimmed.matches("!arliai(?:\\s+(\\d+)h)?(?:\\s+(.*))?")) {
                                if (userId != null && userId.equals(sender)) continue;

                                int hours = 12; // Default to 12 hours
                                String question = null;

                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("!arliai(?:\\s+(\\d+)h)?(?:\\s+(.*))?").matcher(trimmed);
                                if (matcher.matches()) {
                                    if (matcher.group(1) != null) {
                                        hours = Integer.parseInt(matcher.group(1));
                                    }
                                    if (matcher.group(2) != null) {
                                        question = matcher.group(2).trim();
                                    }
                                }

                                System.out.println("Received arliai command in " + roomId + " from " + sender + " (" + (hours > 0 ? hours + "h" : "all history") + ")" + (question != null ? ", question: " + question : ""));
                                final int finalHours = hours;
                                final String finalQuestion = question;
                                final String finalPrevBatch = prevBatch; // Make prevBatch final for lambda
                                final Config finalConfig = config;
                                final String finalRoomId = roomId; // Command room for responses
                                new Thread(() -> queryArliAIWithChatLogs(client, mapper, url, finalConfig.accessToken, finalRoomId, finalConfig.exportRoomId, finalHours, finalPrevBatch, finalQuestion, finalConfig)).start();
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

    private static void queryArliAIWithChatLogs(HttpClient client, ObjectMapper mapper, String url, String accessToken, String responseRoomId, String exportRoomId, int hours, String fromToken, String question, Config config) {
        try {
            sendMarkdown(client, mapper, url, accessToken, responseRoomId, "Querying Arli AI with chat logs from " + exportRoomId + " (last " + (hours > 0 ? hours + "h" : "all history") + (question != null ? " and question: " + question : "") + ")...");

            java.util.List<String> chatLogs = fetchRoomHistory(client, mapper, url, accessToken, exportRoomId, hours, fromToken);
            if (chatLogs.isEmpty()) {
                sendMarkdown(client, mapper, url, accessToken, responseRoomId, "No chat logs found for the last " + (hours > 0 ? hours + "h" : "all history") + " in " + exportRoomId + ".");
                return;
            }

            String prompt = "";
            if (question != null && !question.isEmpty()) {
                prompt = "Given the following chat logs, answer the question: '" + question + "'\\n\\n" + String.join("\\n", chatLogs);
            } else {
                prompt = "Give a high level overview of the following chat logs. Use only a title for each topic and only include one or more chat messages verbatim as bullet points for each topic. Then summarize with bullet points all of the chat at end:\\n\\n" + String.join("\\n", chatLogs);
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
        java.util.List<String> lines = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        long cutoff = now - (long) hours * 3600L * 1000L;

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

                for (JsonNode ev : chunk) {
                    if (!"m.room.message".equals(ev.path("type").asText(null))) continue;
                    long originServerTs = ev.path("origin_server_ts").asLong(0);
                    if (originServerTs < cutoff) {
                        token = null; // Reached messages older than cutoff
                        break;
                    }
                    String body = ev.path("content").path("body").asText(null);
                    String sender = ev.path("sender").asText(null);
                    if (body != null && sender != null) {
                        lines.add("[" + Instant.ofEpochMilli(originServerTs) + "] <" + sender + "> " + body);
                    }
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
}
