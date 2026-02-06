package com.robomwm.ai.matrixrobobot;

/**
 * Handles the !last command: shows user's last message and read receipt status.
 */
public class LastMessageService {
    private final MatrixClient matrixClient;
    private final RoomHistoryManager historyManager;

    public LastMessageService(MatrixClient matrixClient, RoomHistoryManager historyManager) {
        this.matrixClient = matrixClient;
        this.historyManager = historyManager;
    }

    /**
     * Execute the !last command (convenience overload for backward compatibility)
     */
    public void sendLastMessageAndReadReceipt(String exportRoomId, String sender, String responseRoomId) {
        sendLastMessageAndReadReceipt(exportRoomId, sender, responseRoomId, null);
    }

    /**
     * Execute the !last command
     * 
     * @param exportRoomId           The room to get info from
     * @param sender                 The user to get info for
     * @param responseRoomId         The room to send the response to
     * @param cachedPreviousReadInfo Optional cached previous read info (used
     *                               by auto-last feature)
     */
    public void sendLastMessageAndReadReceipt(String exportRoomId, String sender, String responseRoomId,
            RoomHistoryManager.EventInfo cachedPreviousReadInfo) {
        try {
            RoomHistoryManager.EventInfo lastMessageInfo = historyManager.getLastMessageFromSender(exportRoomId,
                    sender);
            // If we have a cached previous read info, use that instead of fetching
            // current
            RoomHistoryManager.EventInfo lastReadInfo = cachedPreviousReadInfo != null
                    ? cachedPreviousReadInfo
                    : historyManager.getReadReceipt(exportRoomId, sender);

            StringBuilder response = new StringBuilder();

            if (lastMessageInfo != null) {
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + lastMessageInfo.eventId;
                response.append("sent: ");
                response.append(messageLink);
                if (lastMessageInfo.timestamp > 0) {
                    response.append(" (").append(formatRelativeTime(lastMessageInfo.timestamp)).append(")");
                }
                response.append("\n");
            } else {
                response.append("No recently sent.\n");
            }

            if (lastReadInfo != null) {
                boolean isLatest = historyManager.isLatestMessage(exportRoomId, lastReadInfo.eventId);
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + lastReadInfo.eventId;

                if (isLatest) {
                    response.append(" no unread. Latest: ");
                    response.append(messageLink);
                    if (lastReadInfo.timestamp > 0) {
                        response.append(" (").append(formatRelativeTime(lastReadInfo.timestamp)).append(")");
                    }
                    response.append("\n");
                } else {
                    int unreadCount = historyManager.countUnreadMessages(exportRoomId, lastReadInfo.eventId);
                    response.append(" read: ");
                    response.append(messageLink);
                    if (lastReadInfo.timestamp > 0 || unreadCount >= 0) {
                        response.append(" (");
                        if (lastReadInfo.timestamp > 0) {
                            response.append(formatRelativeTime(lastReadInfo.timestamp));
                        }
                        if (unreadCount >= 0) {
                            if (lastReadInfo.timestamp > 0)
                                response.append(", ");
                            response.append(unreadCount).append(" unread");
                        }
                        response.append(")");
                    }
                    response.append("\n");
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

    private String formatRelativeTime(long timestamp) {
        long now = System.currentTimeMillis();
        long diff = now - timestamp;
        if (diff < 60000)
            return "just now";
        if (diff < 3600000) {
            long mins = diff / 60000;
            return mins + (mins == 1 ? " minute ago" : " minutes ago");
        }
        if (diff < 86400000) {
            long hours = diff / 3600000;
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        long days = diff / 86400000;
        return days + (days == 1 ? " day ago" : " days ago");
    }
}
