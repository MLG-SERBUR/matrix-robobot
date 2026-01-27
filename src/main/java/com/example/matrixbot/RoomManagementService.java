package com.example.matrixbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

/**
 * Handles room management: joining, leaving, and checking member status.
 */
public class RoomManagementService {
    private final MatrixClient matrixClient;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;

    public RoomManagementService(MatrixClient matrixClient, HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, String accessToken) {
        this.matrixClient = matrixClient;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
    }

    /**
     * Handle auto-join for invited rooms and send encryption warning if needed
     */
    public void handleInvitedRoom(String roomId) {
        if (!matrixClient.joinRoom(roomId)) {
            return;
        }

        // Check if room is encrypted
        if (matrixClient.isRoomEncrypted(roomId)) {
            String warningMessage = "⚠️ **Warning**: This room is end-to-end encrypted. " +
                "I cannot read encrypted messages, so commands will not work. " +
                "Please create an unencrypted room with me for the bot to function properly.";
            matrixClient.sendMarkdown(roomId, warningMessage);
        }
    }

    /**
     * Handle user leaving a room - bot should leave too (for DMs)
     */
    public void handleUserLeftRoom(String roomId, String commandRoomId, String exportRoomId) {
        // Skip configured rooms
        if (roomId.equals(commandRoomId) || roomId.equals(exportRoomId)) {
            System.out.println("Skipping leave for configured room: " + roomId);
            return;
        }

        System.out.println("User left room " + roomId + ", bot is leaving...");
        matrixClient.leaveRoom(roomId);
    }

    /**
     * Clean up abandoned DMs on startup (where user has already left)
     */
    public void cleanupAbandonedDMs(String commandRoomId, String exportRoomId) {
        try {
            System.out.println("Checking for abandoned DMs on startup...");
            java.util.List<String> joinedRooms = matrixClient.getJoinedRooms();

            for (String roomId : joinedRooms) {
                // Skip configured rooms
                if (roomId.equals(commandRoomId) || roomId.equals(exportRoomId)) {
                    continue;
                }

                // Check room member count
                int memberCount = matrixClient.getRoomMemberCount(roomId);
                if (memberCount <= 1) {
                    System.out.println("Startup: Room " + roomId + " has " + memberCount + " member(s), bot is leaving");
                    matrixClient.leaveRoom(roomId);
                }
            }
        } catch (Exception e) {
            System.out.println("Error checking for abandoned DMs: " + e.getMessage());
        }
    }
}
