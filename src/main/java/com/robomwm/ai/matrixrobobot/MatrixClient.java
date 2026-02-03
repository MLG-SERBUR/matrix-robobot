package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles all Matrix protocol interactions including sending messages,
 * getting user info, managing room state, etc.
 */
public class MatrixClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;

    public MatrixClient(HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, String accessToken) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl.endsWith("/")
                ? homeserverUrl.substring(0, homeserverUrl.length() - 1)
                : homeserverUrl;
        this.accessToken = accessToken;
    }

    /**
     * Get the current user's ID
     */
    public String getUserId() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(homeserverUrl + "/_matrix/client/v3/account/whoami"))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                String userId = root.path("user_id").asText(null);
                if (userId != null) {
                    System.out.println("Detected user id: " + userId);
                }
                return userId;
            } else {
                System.out.println("whoami returned: " + response.statusCode());
            }
        } catch (Exception e) {
            System.out.println("whoami failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Send a plain text message to a room
     */
    public void sendText(String roomId, String message) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = homeserverUrl + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/"
                    + txnId;

            String sanitizedMessage = sanitizeUserIds(message);

            Map<String, Object> payload = new HashMap<>();
            payload.put("msgtype", "m.text");
            payload.put("body", sanitizedMessage);
            payload.put("m.mentions", Map.of());
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Sent reply to " + roomId + " -> " + response.statusCode());
        } catch (Exception e) {
            System.out.println("Failed to send message: " + e.getMessage());
        }
    }

    /**
     * Send a text message and return the event ID
     */
    public String sendTextWithEventId(String roomId, String message) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = homeserverUrl + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/"
                    + txnId;

            String sanitizedMessage = sanitizeUserIds(message);

            Map<String, Object> payload = new HashMap<>();
            payload.put("msgtype", "m.text");
            payload.put("body", sanitizedMessage);
            payload.put("m.mentions", Map.of());
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                return root.path("event_id").asText(null);
            }
            System.out.println("Sent message to " + roomId + " -> " + response.statusCode());
            return null;
        } catch (Exception e) {
            System.out.println("Failed to send message: " + e.getMessage());
            return null;
        }
    }

    /**
     * Update a previously sent text message
     */
    public String updateTextMessage(String roomId, String originalEventId, String message) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = homeserverUrl + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/"
                    + txnId;

            String sanitizedMessage = sanitizeUserIds(message);

            Map<String, Object> payload = new HashMap<>();
            payload.put("msgtype", "m.text");
            payload.put("body", "* " + sanitizedMessage);
            payload.put("m.mentions", Map.of());

            Map<String, Object> newContent = new HashMap<>();
            newContent.put("msgtype", "m.text");
            newContent.put("body", sanitizedMessage);
            newContent.put("m.mentions", Map.of());
            payload.put("m.new_content", newContent);

            Map<String, Object> relatesTo = new HashMap<>();
            relatesTo.put("event_id", originalEventId);
            relatesTo.put("rel_type", "m.replace");
            payload.put("m.relates_to", relatesTo);

            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Updated message " + originalEventId + " -> " + response.statusCode());

            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                return root.path("event_id").asText(null);
            }
        } catch (Exception e) {
            System.out.println("Failed to update message: " + e.getMessage());
        }
        return null;
    }

    /**
     * Send a markdown formatted message
     */
    public void sendMarkdown(String roomId, String message) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = homeserverUrl + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/"
                    + txnId;

            String sanitizedMessage = sanitizeUserIds(message);
            String htmlBody = convertMarkdownToHtml(sanitizedMessage);

            Map<String, Object> payload = new HashMap<>();
            payload.put("msgtype", "m.text");
            payload.put("body", sanitizedMessage);
            payload.put("format", "org.matrix.custom.html");
            payload.put("formatted_body", htmlBody);
            payload.put("m.mentions", Map.of());
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Sent markdown reply to " + roomId + " -> " + response.statusCode());
        } catch (Exception e) {
            System.out.println("Failed to send markdown message: " + e.getMessage());
        }
    }

    /**
     * Join a room
     */
    public boolean joinRoom(String roomId) {
        try {
            String joinUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                    + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/join";
            Map<String, Object> payload = new HashMap<>();
            String jsonPayload = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(joinUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.out.println("Successfully joined room: " + roomId);
                return true;
            } else {
                System.out.println(
                        "Failed to join room " + roomId + ": " + response.statusCode() + " - " + response.body());
                return false;
            }
        } catch (Exception e) {
            System.out.println("Error joining room: " + e.getMessage());
            return false;
        }
    }

    /**
     * Leave a room
     */
    public boolean leaveRoom(String roomId) {
        try {
            String leaveUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                    + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/leave";
            Map<String, Object> payload = new HashMap<>();
            String jsonPayload = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(leaveUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Bot left room " + roomId + " -> " + response.statusCode());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.out.println("Error leaving room: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if a room is encrypted
     */
    public boolean isRoomEncrypted(String roomId) {
        try {
            String stateUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                    + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/state/m.room.encryption/";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stateUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode encryption = mapper.readTree(response.body());
                if (encryption.has("algorithm")) {
                    System.out.println("Room " + roomId + " is encrypted with algorithm: "
                            + encryption.path("algorithm").asText());
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            System.out.println("Error checking room encryption: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the member count of a room
     */
    public int getRoomMemberCount(String roomId) {
        try {
            String membersUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                    + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/members";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(membersUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode members = mapper.readTree(response.body()).path("chunk");
                if (members.isArray()) {
                    int count = 0;
                    for (JsonNode member : members) {
                        String membership = member.path("content").path("membership").asText(null);
                        if ("join".equals(membership) || "invite".equals(membership)) {
                            count++;
                        }
                    }
                    return count;
                }
            }
        } catch (Exception e) {
            System.out.println("Error getting room member count: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Get list of joined rooms
     */
    public java.util.List<String> getJoinedRooms() {
        java.util.List<String> rooms = new java.util.ArrayList<>();
        try {
            String joinedRoomsUrl = homeserverUrl + "/_matrix/client/v3/joined_rooms";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(joinedRoomsUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode joinedRooms = mapper.readTree(response.body()).path("joined_rooms");
                if (joinedRooms.isArray()) {
                    for (JsonNode roomId : joinedRooms) {
                        rooms.add(roomId.asText());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error getting joined rooms: " + e.getMessage());
        }
        return rooms;
    }

    /**
     * Convert markdown to HTML using commonmark
     */
    private String convertMarkdownToHtml(String markdown) {
        Parser parser = Parser.builder().build();
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        org.commonmark.node.Node document = parser.parse(markdown);
        return renderer.render(document);
    }

    /**
     * Sanitize user IDs to prevent pinging
     */
    private String sanitizeUserIds(String message) {
        return message.replaceAll("(?<!`)(?<!`<)(@[a-zA-Z0-9._=-]+:[a-zA-Z0-9.-]+)(?!`)", "`$1`");
    }

    public String getHomeserverUrl() {
        return homeserverUrl;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public ObjectMapper getMapper() {
        return mapper;
    }
}
