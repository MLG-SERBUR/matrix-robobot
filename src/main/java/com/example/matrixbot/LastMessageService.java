package com.example.matrixbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Handles the !last command: shows user's last message and read receipt status.
 */
public class LastMessageService {
    private final MatrixClient matrixClient;
    private final RoomHistoryManager historyManager;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;

    public LastMessageService(MatrixClient matrixClient, RoomHistoryManager historyManager, HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, String accessToken) {
        this.matrixClient = matrixClient;
        this.historyManager = historyManager;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
    }

    /**
     * Execute the !last command
     */
    public void sendLastMessageAndReadReceipt(String exportRoomId, String sender, String responseRoomId) {
        try {
            String lastMessageEventId = historyManager.getLastMessageFromSender(exportRoomId, sender);
            String lastReadEventId = getReadReceipt(exportRoomId, sender);

            StringBuilder response = new StringBuilder();

            if (lastMessageEventId != null) {
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + lastMessageEventId;
                response.append("sent: ");
                response.append(messageLink).append("\n");
            } else {
                response.append("No recently sent.\n");
            }

            if (lastReadEventId != null) {
                boolean isLatest = isLatestMessage(exportRoomId, lastReadEventId);
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + lastReadEventId;

                if (isLatest) {
                    response.append(" no unread. Latest: ");
                    response.append(messageLink).append("\n");
                } else {
                    response.append(" read: ");
                    response.append(messageLink).append("\n");
                }
            } else {
                response.append("No read receipt found.\n");
            }

            matrixClient.sendMarkdown(responseRoomId, response.toString());

        } catch (Exception e) {
            System.out.println("Failed to get last message info: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Error getting last message info: " + e.getMessage());
        }
    }

    /**
     * Get read receipt for a user in a room
     */
    private String getReadReceipt(String roomId, String userId) {
        try {
            Map<Long, java.util.List<String>> receiptsWithTimestamps = new TreeMap<>(Collections.reverseOrder());

            // Try to get the read receipt from the sync response first
            String syncUrl = homeserverUrl + "/_matrix/client/v3/sync?timeout=0";
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(syncUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> syncResp = httpClient.send(syncReq, HttpResponse.BodyHandlers.ofString());

            if (syncResp.statusCode() == 200) {
                JsonNode root = mapper.readTree(syncResp.body());
                JsonNode roomNode = root.path("rooms").path("join").path(roomId);
                if (!roomNode.isMissingNode()) {
                    JsonNode ephemeral = roomNode.path("ephemeral").path("events");
                    if (ephemeral.isArray()) {
                        for (JsonNode ev : ephemeral) {
                            if ("m.receipt".equals(ev.path("type").asText(null))) {
                                JsonNode content = ev.path("content");
                                java.util.Iterator<String> eventIds = content.fieldNames();
                                while (eventIds.hasNext()) {
                                    String eventId = eventIds.next();
                                    JsonNode receiptData = content.path(eventId).path("m.read");
                                    if (receiptData.has(userId)) {
                                        JsonNode timestampNode = receiptData.path(userId);
                                        long timestamp = 0;

                                        if (timestampNode.isObject() && timestampNode.has("ts")) {
                                            timestamp = timestampNode.path("ts").asLong(0);
                                        } else {
                                            timestamp = timestampNode.asLong(0);
                                        }

                                        if (timestamp == 0) {
                                            timestamp = eventId.hashCode();
                                        }

                                        receiptsWithTimestamps.computeIfAbsent(timestamp, k -> new java.util.ArrayList<>()).add(eventId);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Also check room account data for the most recent read receipt
            String encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
            String encodedUser = URLEncoder.encode(userId, StandardCharsets.UTF_8);
            String accountDataUrl = homeserverUrl + "/_matrix/client/v3/user/" + encodedUser + "/rooms/" + encodedRoom + "/account_data/m.read";
            HttpRequest accountReq = HttpRequest.newBuilder()
                    .uri(URI.create(accountDataUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> accountResp = httpClient.send(accountReq, HttpResponse.BodyHandlers.ofString());

            if (accountResp.statusCode() == 200) {
                JsonNode accountData = mapper.readTree(accountResp.body());
                String lastRead = accountData.path("event_id").asText(null);
                if (lastRead != null && !lastRead.isEmpty()) {
                    long accountDataTimestamp = Long.MAX_VALUE - 1;
                    receiptsWithTimestamps.computeIfAbsent(accountDataTimestamp, k -> new java.util.ArrayList<>()).add(lastRead);
                }
            }

            if (!receiptsWithTimestamps.isEmpty()) {
                Map.Entry<Long, java.util.List<String>> firstEntry = receiptsWithTimestamps.entrySet().iterator().next();
                return firstEntry.getValue().get(firstEntry.getValue().size() - 1);
            }

            return null;

        } catch (Exception e) {
            System.out.println("Error getting read receipt: " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if a message is the latest in the room
     */
    private boolean isLatestMessage(String roomId, String eventId) {
        try {
            String syncUrl = homeserverUrl + "/_matrix/client/v3/sync?timeout=0";
            HttpRequest syncReq = HttpRequest.newBuilder()
                    .uri(URI.create(syncUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> syncResp = httpClient.send(syncReq, HttpResponse.BodyHandlers.ofString());

            if (syncResp.statusCode() != 200) {
                return false;
            }

            JsonNode root = mapper.readTree(syncResp.body());
            JsonNode roomNode = root.path("rooms").path("join").path(roomId);
            if (roomNode.isMissingNode()) {
                return false;
            }

            JsonNode timeline = roomNode.path("timeline").path("events");
            if (timeline.isArray() && timeline.size() > 0) {
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
}
