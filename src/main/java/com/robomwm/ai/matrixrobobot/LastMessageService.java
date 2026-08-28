package com.robomwm.ai.matrixrobobot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Handles the !last command: shows user's last message and read receipt status.
 */
public class LastMessageService {
    private static final DateTimeFormatter ABSOLUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final MatrixClient matrixClient;
    private final RoomHistoryManager historyManager;
    private final TimezoneService timezoneService;

    public LastMessageService(MatrixClient matrixClient, RoomHistoryManager historyManager) {
        this(matrixClient, historyManager, null);
    }

    public LastMessageService(MatrixClient matrixClient, RoomHistoryManager historyManager, TimezoneService timezoneService) {
        this.matrixClient = matrixClient;
        this.historyManager = historyManager;
        this.timezoneService = timezoneService;
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
            RoomHistoryManager.EventInfo latestMessageInfo = historyManager.getLatestMessage(exportRoomId);

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

                    // Include unread mentions/replies as clickable timestamps (combined, no room mentions)
                    try {
                        List<RoomHistoryManager.EventInfo> mentions = historyManager.findUnreadMentionsAndReplies(
                                exportRoomId, lastReadInfo.eventId, sender);
                        if (!mentions.isEmpty()) {
                            ZoneId zoneId = timezoneService != null ? timezoneService.getZoneIdForUser(sender) : null;
                            response.append("mentions: ");
                            int displayLimit = 50;
                            int displayCount = Math.min(mentions.size(), displayLimit);
                            for (int i = 0; i < displayCount; i++) {
                                RoomHistoryManager.EventInfo info = mentions.get(i);
                                String tsText;
                                if (zoneId != null && info.timestamp > 0) {
                                    tsText = Instant.ofEpochMilli(info.timestamp).atZone(zoneId).format(ABSOLUTE_FORMATTER);
                                } else if (info.timestamp > 0) {
                                    tsText = formatRelativeTime(info.timestamp);
                                } else {
                                    tsText = info.eventId;
                                }
                                String link = "https://matrix.to/#/" + exportRoomId + "/" + info.eventId;
                                response.append("[").append(tsText).append("](").append(link).append(")");
                                if (i < displayCount - 1) response.append(", ");
                            }
                            if (mentions.size() > displayLimit) {
                                response.append(", and ").append(mentions.size() - displayLimit).append(" more");
                            }
                            response.append("\n");
                        }
                    } catch (Exception e) {
                        System.out.println("Failed to fetch mentions/replies: " + e.getMessage());
                    }
                }
            } else {
                response.append("No read receipt found.\n");
            }

            // This response represents the unread window: immediately after the
            // user's last read receipt through the room's current latest message.
            Map<String, Object> botContext = AIService.buildHistoryContext(
                    exportRoomId,
                    lastReadInfo != null ? lastReadInfo.eventId : null,
                    latestMessageInfo != null ? latestMessageInfo.eventId : null,
                    false);
            Map<String, Object> extra = new java.util.HashMap<>();
            extra.put("ai.matrixrobobot.context", botContext);

            matrixClient.sendMarkdownWithEventId(responseRoomId, response.toString(), extra);

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
