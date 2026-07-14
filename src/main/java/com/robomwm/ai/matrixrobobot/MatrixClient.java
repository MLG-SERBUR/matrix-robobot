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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all Matrix protocol interactions including sending messages,
 * getting user info, managing room state, etc.
 */
public class MatrixClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;
    private final Map<String, String> displayNameCache = new ConcurrentHashMap<>();
    private MatrixMessageQueue messageQueue;

    public MatrixClient(HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, String accessToken) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl.endsWith("/")
                ? homeserverUrl.substring(0, homeserverUrl.length() - 1)
                : homeserverUrl;
        this.accessToken = accessToken;
        // Initialize message queue - will be lazily created on first use
        this.messageQueue = null;
    }
    
    /**
     * Get or create the message queue for this client
     */
    private MatrixMessageQueue getMessageQueue() {
        if (messageQueue == null) {
            messageQueue = MatrixMessageQueue.getInstance(httpClient, mapper, homeserverUrl, accessToken);
        }
        return messageQueue;
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
        sendMessage(roomId, message, false, "m.text");
    }

    public void sendNotice(String roomId, String message) {
        sendMessage(roomId, message, false, "m.notice");
    }

    /**
     * Send a text message and return the event ID
     */
    public String sendTextWithEventId(String roomId, String message) {
        return sendMessageWithEventId(roomId, message, false, "m.text");
    }

    public String sendNoticeWithEventId(String roomId, String message) {
        return sendMessageWithEventId(roomId, message, false, "m.notice");
    }

    private void sendMessage(String roomId, String message, boolean useMarkdown, String msgType) {
        sendMessageWithEventId(roomId, message, useMarkdown, msgType);
    }

    private String sendMessageWithEventId(String roomId, String message, boolean useMarkdown, String msgType) {
        // Use the message queue for all sends
        if ("m.notice".equals(msgType)) {
            if (useMarkdown) {
                return getMessageQueue().sendMarkdownNoticeWithEventId(roomId, message);
            } else {
                return getMessageQueue().sendNoticeWithEventId(roomId, message);
            }
        } else {
            if (useMarkdown) {
                return getMessageQueue().sendMarkdownWithEventId(roomId, message);
            } else {
                return getMessageQueue().sendTextWithEventId(roomId, message);
            }
        }
    }

    /**
     * Update a previously sent text message
     */
    public String updateTextMessage(String roomId, String originalEventId, String message) {
        return updateMessage(roomId, originalEventId, message, false, "m.text");
    }

    public String updateNoticeMessage(String roomId, String originalEventId, String message) {
        return updateMessage(roomId, originalEventId, message, false, "m.notice");
    }

    public String updateMarkdownMessage(String roomId, String originalEventId, String message) {
        return updateMessage(roomId, originalEventId, message, true, "m.text");
    }

    public String updateMarkdownNoticeMessage(String roomId, String originalEventId, String message) {
        return updateMessage(roomId, originalEventId, message, true, "m.notice");
    }

    private String updateMessage(String roomId, String originalEventId, String message, boolean useMarkdown, String msgType) {
        // Use the message queue for all updates
        if ("m.notice".equals(msgType)) {
            if (useMarkdown) {
                return getMessageQueue().updateMarkdownNoticeMessage(roomId, originalEventId, message);
            } else {
                return getMessageQueue().updateNoticeMessage(roomId, originalEventId, message);
            }
        } else {
            if (useMarkdown) {
                return getMessageQueue().updateMarkdownMessage(roomId, originalEventId, message);
            } else {
                return getMessageQueue().updateTextMessage(roomId, originalEventId, message);
            }
        }
    }

    /**
     * Send a markdown formatted message and return the event ID
     */
    public String sendMarkdownWithEventId(String roomId, String message) {
        return sendMessageWithEventId(roomId, message, true, "m.text");
    }

    public String sendMarkdownNoticeWithEventId(String roomId, String message) {
        return sendMessageWithEventId(roomId, message, true, "m.notice");
    }

    /**
     * Send a markdown formatted message
     */
    public void sendMarkdown(String roomId, String message) {
        sendMessage(roomId, message, true, "m.text");
    }

    public void sendMarkdownNotice(String roomId, String message) {
        sendMessage(roomId, message, true, "m.notice");
    }

    /**
     * Send a reaction to an event
     */
    public void sendReaction(String roomId, String eventId, String reaction) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String endpoint = homeserverUrl + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.reaction/"
                    + txnId;

            Map<String, Object> payload = new HashMap<>();

            Map<String, Object> relatesTo = new HashMap<>();
            relatesTo.put("event_id", eventId);
            relatesTo.put("key", reaction);
            relatesTo.put("rel_type", "m.annotation");
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
            System.out.println("Sent reaction " + reaction + " to " + eventId + " -> " + response.statusCode());
        } catch (Exception e) {
            System.out.println("Failed to send reaction: " + e.getMessage());
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
     * Get a list of user IDs for joined or invited room members
     */
    public java.util.List<String> getRoomMemberIds(String roomId) {
        java.util.List<String> memberIds = new java.util.ArrayList<>();
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
                    for (JsonNode member : members) {
                        String membership = member.path("content").path("membership").asText(null);
                        if ("join".equals(membership) || "invite".equals(membership)) {
                            String userId = member.path("state_key").asText(null);
                            if (userId != null) {
                                memberIds.add(userId);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error getting room member IDs: " + e.getMessage());
        }
        return memberIds;
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

    public String getDisplayName(String userId) {
        if (userId == null) return null;
        if (displayNameCache.containsKey(userId)) {
            return displayNameCache.get(userId);
        }

        try {
            String encodedId = URLEncoder.encode(userId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(homeserverUrl + "/_matrix/client/v3/profile/" + encodedId + "/displayname"))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                String displayName = root.path("displayname").asText(null);
                if (displayName != null) {
                    displayNameCache.put(userId, displayName);
                    return displayName;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch display name for " + userId + ": " + e.getMessage());
        }

        // Return userID as fallback if no display name found or error occurred
        return userId;
    }

    /**
     * Sanitize user IDs to prevent pinging (shared with MatrixMessageQueue)
     */
    public static String sanitizeUserIdsStatic(String message) {
        if (message == null) return null;
        // Wrap @room, @everyone, @channel mentions to prevent pings
        String sanitized = message.replaceAll("(?<!`)(?<!`<)(@(room|everyone|channel))(?!`)", "`$1`");
        // Wrap @user:domain mentions to prevent pings
        return sanitized.replaceAll("(?<!`)(?<!`<)(@[a-zA-Z0-9._=-]+:[a-zA-Z0-9.-]+)(?!`)", "`$1`");
    }

    /**
     * Sanitize user IDs to prevent pinging (instance wrapper)
     */
    private String sanitizeUserIds(String message) {
        return sanitizeUserIdsStatic(message);
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
