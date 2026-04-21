package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Iterator;

/**
 * Manages fetching and processing room chat history from the Matrix server.
 */
public class RoomHistoryManager {
    private static final DateTimeFormatter LEGACY_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
    private static final DateTimeFormatter AI_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter AI_TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm");

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int messageCount, int estimatedTokens);
    }

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;

    private static class RawLogLine {
        final long timestamp;
        final String sender;
        final String body;

        RawLogLine(long timestamp, String sender, String body) {
            this.timestamp = timestamp;
            this.sender = sender;
            this.body = body;
        }
    }

    public static class ChatLogsResult {
        public List<String> logs;
        public String firstEventId;
        public String errorMessage;
        // Optional fields for image support (null for text-only commands)
        public List<String> imageUrls;
        public List<String> imageCaptions;

        public ChatLogsResult(List<String> logs, String firstEventId) {
            this(logs, firstEventId, null);
        }

        public ChatLogsResult(List<String> logs, String firstEventId, String errorMessage) {
            this.logs = logs;
            this.firstEventId = firstEventId;
            this.errorMessage = errorMessage;
            this.imageUrls = null;
            this.imageCaptions = null;
        }

        // Constructor for vision commands
        public ChatLogsResult(List<String> logs, String firstEventId, String errorMessage,
                            List<String> imageUrls, List<String> imageCaptions) {
            this.logs = logs;
            this.firstEventId = firstEventId;
            this.errorMessage = errorMessage;
            this.imageUrls = imageUrls;
            this.imageCaptions = imageCaptions;
        }
    }

    public static class ChatLogsWithIds {
        public List<String> logs;
        public List<String> eventIds;

        public ChatLogsWithIds(List<String> logs, List<String> eventIds) {
            this.logs = logs;
            this.eventIds = eventIds;
        }
    }

    public static class EventInfo {
        public String eventId;
        public long timestamp;

        public EventInfo(String eventId, long timestamp) {
            this.eventId = eventId;
            this.timestamp = timestamp;
        }
    }

    public static class TokenResult {
        public String token;
        public long timestamp;
        public String errorMessage;

        public TokenResult(String token, long timestamp) {
            this(token, timestamp, null);
        }

        public TokenResult(String token, long timestamp, String errorMessage) {
            this.token = token;
            this.timestamp = timestamp;
            this.errorMessage = errorMessage;
        }

        public static TokenResult error(String message) {
            return new TokenResult(null, 0, message);
        }
    }

    public RoomHistoryManager(HttpClient httpClient, ObjectMapper mapper, String homeserverUrl, String accessToken) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
    }

    private ZoneId normalizeZoneId(ZoneId zoneId) {
        return zoneId != null ? zoneId : ZoneId.of("UTC");
    }

    private String formatLogLine(RawLogLine line, ZoneId zoneId, LocalDate previousDate, boolean aiFriendlyTimestamps) {
        ZoneId effectiveZoneId = normalizeZoneId(zoneId);
        var zonedTimestamp = Instant.ofEpochMilli(line.timestamp).atZone(effectiveZoneId);
        String timestamp;
        if (aiFriendlyTimestamps) {
            String timePart = zonedTimestamp.format(AI_TIME_FORMATTER);
            LocalDate currentDate = zonedTimestamp.toLocalDate();
            timestamp = (previousDate == null || !previousDate.equals(currentDate))
                    ? zonedTimestamp.format(AI_DATE_FORMATTER) + " " + timePart
                    : timePart;
        } else {
            timestamp = zonedTimestamp.format(LEGACY_TIMESTAMP_FORMATTER);
        }
        return "[" + timestamp + "] <" + line.sender + "> " + line.body;
    }

    private List<String> formatLogLines(List<RawLogLine> rawLines, ZoneId zoneId, boolean aiFriendlyTimestamps) {
        List<String> formatted = new ArrayList<>(rawLines.size());
        LocalDate previousDate = null;
        ZoneId effectiveZoneId = normalizeZoneId(zoneId);
        for (RawLogLine line : rawLines) {
            var zonedTimestamp = Instant.ofEpochMilli(line.timestamp).atZone(effectiveZoneId);
            formatted.add(formatLogLine(line, effectiveZoneId, previousDate, aiFriendlyTimestamps));
            previousDate = zonedTimestamp.toLocalDate();
        }
        return formatted;
    }

    private int estimateFormattedTokens(List<RawLogLine> rawLines, ZoneId zoneId, boolean aiFriendlyTimestamps) {
        int tokens = 0;
        LocalDate previousDate = null;
        ZoneId effectiveZoneId = normalizeZoneId(zoneId);
        for (RawLogLine line : rawLines) {
            var zonedTimestamp = Instant.ofEpochMilli(line.timestamp).atZone(effectiveZoneId);
            tokens += estimateTokens(formatLogLine(line, effectiveZoneId, previousDate, aiFriendlyTimestamps));
            previousDate = zonedTimestamp.toLocalDate();
        }
        return tokens;
    }

    /**
     * Fetch room history as simple log strings
     */
    public List<String> fetchRoomHistory(String roomId, int hours, String fromToken) {
        return fetchRoomHistory(roomId, hours, fromToken, null);
    }

    public List<String> fetchRoomHistory(String roomId, int hours, String fromToken, java.util.concurrent.atomic.AtomicBoolean abortFlag) {
        ChatLogsResult result = fetchRoomHistoryDetailed(roomId, hours, fromToken, -1, -1,
                ZoneId.of("America/Los_Angeles"), -1, abortFlag);
        return result.logs;
    }

    /**
     * Fetch room history with event IDs
     */
    public ChatLogsWithIds fetchRoomHistoryWithIds(String roomId, int hours, String fromToken, long startTimestamp,
            long endTime, ZoneId zoneId) {
        return fetchRoomHistoryWithIds(roomId, hours, fromToken, startTimestamp, endTime, zoneId, false, null, null);
    }

    public ChatLogsWithIds fetchRoomHistoryWithIds(String roomId, int hours, String fromToken, long startTimestamp,
            long endTime, ZoneId zoneId, java.util.concurrent.atomic.AtomicBoolean abortFlag) {
        return fetchRoomHistoryWithIds(roomId, hours, fromToken, startTimestamp, endTime, zoneId, false, abortFlag, null);
    }

    public ChatLogsWithIds fetchRoomHistoryWithIds(String roomId, int hours, String fromToken, long startTimestamp,
            long endTime, ZoneId zoneId, java.util.concurrent.atomic.AtomicBoolean abortFlag, ProgressCallback progressCallback) {
        return fetchRoomHistoryWithIds(roomId, hours, fromToken, startTimestamp, endTime, zoneId, false, abortFlag, progressCallback);
    }

    public ChatLogsWithIds fetchRoomHistoryWithIds(String roomId, int hours, String fromToken, long startTimestamp,
            long endTime, ZoneId zoneId, boolean aiFriendlyTimestamps, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            ProgressCallback progressCallback) {
        List<RawLogLine> rawLines = new ArrayList<>();
        List<String> eventIds = new ArrayList<>();

        long startTime = (startTimestamp > 0) ? startTimestamp
                : System.currentTimeMillis() - (long) hours * 3600L * 1000L;
        long calculatedEndTime = (endTime > 0) ? endTime : System.currentTimeMillis();

        String token = getPaginationToken(roomId, fromToken);

        while (token != null) {
            if (abortFlag != null && abortFlag.get()) {
                System.out.println("fetchRoomHistoryWithIds aborted.");
                break;
            }
            try {
                String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                        + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                HttpRequest msgReq = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl))
                        .header("Authorization", "Bearer " + accessToken)
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build();
                HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
                if (msgResp.statusCode() != 200) {
                    System.out.println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                    break;
                }
                JsonNode root = mapper.readTree(msgResp.body());
                JsonNode chunk = root.path("chunk");
                if (!chunk.isArray() || chunk.size() == 0)
                    break;

                boolean reachedStart = false;
                for (JsonNode ev : chunk) {
                    if (!"m.room.message".equals(ev.path("type").asText(null)))
                        continue;
                    long originServerTs = ev.path("origin_server_ts").asLong(0);

                    if (originServerTs > calculatedEndTime) {
                        continue;
                    }

                    if (originServerTs < startTime) {
                        reachedStart = true;
                        break;
                    }

                    String body = ev.path("content").path("body").asText(null);
                    String sender = ev.path("sender").asText(null);
                    String eventId = ev.path("event_id").asText(null);
                    if (body != null && sender != null && eventId != null) {
                        rawLines.add(new RawLogLine(originServerTs, sender, body));
                        eventIds.add(eventId);
                    }
                }

                // Report progress after each batch
                if (progressCallback != null && !rawLines.isEmpty()) {
                    progressCallback.onProgress(rawLines.size(), estimateFormattedTokens(rawLines, zoneId, aiFriendlyTimestamps));
                }

                if (reachedStart) {
                    break;
                }

                token = root.path("end").asText(null);

            } catch (Exception e) {
                System.out.println("Error fetching room history: " + e.getMessage());
                break;
            }
        }
        Collections.reverse(rawLines);
        Collections.reverse(eventIds);
        return new ChatLogsWithIds(formatLogLines(rawLines, zoneId, aiFriendlyTimestamps), eventIds);
    }

    /**
     * Fetch room history starting from a specific event ID or latest if null.
     */
    public ChatLogsResult fetchRoomHistoryRelative(String roomId, int hours, String fromToken, String startEventId,
            boolean forward, ZoneId zoneId, int maxMessages) {
        return fetchRoomHistoryRelative(roomId, hours, fromToken, startEventId, forward, zoneId, maxMessages, false, false, null, null);
    }

    public ChatLogsResult fetchRoomHistoryRelative(String roomId, int hours, String fromToken, String startEventId,
            boolean forward, ZoneId zoneId, int maxMessages, java.util.concurrent.atomic.AtomicBoolean abortFlag) {
        return fetchRoomHistoryRelative(roomId, hours, fromToken, startEventId, forward, zoneId, maxMessages, false, false, abortFlag, null);
    }

    public ChatLogsResult fetchRoomHistoryRelative(String roomId, int hours, String fromToken, String startEventId,
            boolean forward, ZoneId zoneId, int maxMessages, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            ProgressCallback progressCallback) {
        return fetchRoomHistoryRelative(roomId, hours, fromToken, startEventId, forward, zoneId, maxMessages, false, false, abortFlag, progressCallback);
    }

    // New method with image collection
    public ChatLogsResult fetchRoomHistoryRelative(String roomId, int hours, String fromToken, String startEventId,
            boolean forward, ZoneId zoneId, int maxMessages, boolean collectImages, boolean aiFriendlyTimestamps, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            ProgressCallback progressCallback) {
        if (startEventId == null) {
            return fetchRoomHistoryDetailed(roomId, hours, fromToken, -1, -1, zoneId, maxMessages, collectImages, aiFriendlyTimestamps, abortFlag, progressCallback);
        }

        List<RawLogLine> rawLines = new ArrayList<>();
        List<String> imageUrls = collectImages ? new ArrayList<>() : null;
        List<String> imageCaptions = collectImages ? new ArrayList<>() : null;
        String firstEventId = null;

        TokenResult tokenRes = getTokenForEvent(roomId, startEventId, forward);
        if (tokenRes == null || tokenRes.errorMessage != null) {
            return new ChatLogsResult(formatLogLines(rawLines, zoneId, aiFriendlyTimestamps), null, tokenRes != null ? tokenRes.errorMessage : "Failed to get token for event " + startEventId);
        }

        String token = tokenRes.token;
        long eventTs = tokenRes.timestamp;

        // If forward: end time = eventTs + hours
        // If backward: start time = eventTs - hours
        long searchFloor = -1;
        long searchCeiling = -1;

        if (hours > 0) {
            if (forward) {
                searchCeiling = eventTs + (long) hours * 3600L * 1000L;
            } else {
                searchFloor = eventTs - (long) hours * 3600L * 1000L;
            }
        }

        String dir = forward ? "f" : "b";

        while (token != null) {
            if (abortFlag != null && abortFlag.get()) {
                System.out.println("fetchRoomHistoryRelative aborted.");
                break;
            }
            try {
                String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                        + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                        + "&dir=" + dir + "&limit=1000";
                HttpRequest msgReq = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl))
                        .header("Authorization", "Bearer " + accessToken)
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build();
                HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
                if (msgResp.statusCode() != 200) {
                    System.out.println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                    break;
                }
                JsonNode root = mapper.readTree(msgResp.body());
                JsonNode chunk = root.path("chunk");
                if (!chunk.isArray() || chunk.size() == 0)
                    break;

                boolean stop = false;
                for (JsonNode ev : chunk) {
                    if (!"m.room.message".equals(ev.path("type").asText(null)))
                        continue;
                    long originServerTs = ev.path("origin_server_ts").asLong(0);

                    if (!forward && searchFloor > 0 && originServerTs < searchFloor) {
                        stop = true;
                        break;
                    }
                    if (forward && searchCeiling > 0 && originServerTs > searchCeiling) {
                        stop = true;
                        break;
                    }

                    String body = ev.path("content").path("body").asText(null);
                    String sender = ev.path("sender").asText(null);
                    String eventId = ev.path("event_id").asText(null);
                    String msgtype = ev.path("content").path("msgtype").asText(null);
                    if (body != null && sender != null) {
                        rawLines.add(new RawLogLine(originServerTs, sender, body));

                        if (firstEventId == null)
                            firstEventId = eventId;

                        // Collect image URLs if enabled
                        if (collectImages && "m.image".equals(msgtype)) {
                            String imageUrl = ev.path("content").path("url").asText(null);
                            if (imageUrl != null && !imageUrl.isEmpty()) {
                                imageUrls.add(imageUrl);
                                imageCaptions.add(body);
                            }
                        }

                        if (maxMessages > 0 && rawLines.size() >= maxMessages) {
                            stop = true;
                            break;
                        }
                    }
                }

                // Report progress after each batch
                if (progressCallback != null && !rawLines.isEmpty()) {
                    progressCallback.onProgress(rawLines.size(), estimateFormattedTokens(rawLines, zoneId, aiFriendlyTimestamps));
                }

                if (stop) {
                    break;
                }

                token = root.path("end").asText(null);

            } catch (Exception e) {
                System.out.println("Error fetching room history relative: " + e.getMessage());
                break;
            }
        }
        
        if (!forward) {
            Collections.reverse(rawLines);
            if (collectImages) {
                Collections.reverse(imageUrls);
                Collections.reverse(imageCaptions);
            }
        }

        return new ChatLogsResult(formatLogLines(rawLines, zoneId, aiFriendlyTimestamps), firstEventId, null, imageUrls, imageCaptions);
    }

    private TokenResult getTokenForEvent(String roomId, String eventId, boolean forward) {
        try {
            String url = homeserverUrl + "/_matrix/client/v3/rooms/"
                    + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                    + "/context/" + URLEncoder.encode(eventId, StandardCharsets.UTF_8) + "?limit=0";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                String token = root.path(forward ? "end" : "start").asText(null);
                long ts = root.path("event").path("origin_server_ts").asLong(0);
                return new TokenResult(token, ts);
            }
            String errorMsg = "Failed to get token for event " + eventId + ": " + resp.statusCode();
            System.out.println(errorMsg);
            return TokenResult.error(errorMsg);
        } catch (Exception e) {
            String errorMsg = "Error getting token for event " + eventId + ": " + e.getMessage();
            System.out.println(errorMsg);
            return TokenResult.error(errorMsg);
        }
    }

    /**
     * Fetch room history with first event ID tracking
     */
    public ChatLogsResult fetchRoomHistoryDetailed(String roomId, int hours, String fromToken, long startTimestamp,
            long endTime, ZoneId zoneId, int maxMessages) {
        return fetchRoomHistoryDetailed(roomId, hours, fromToken, startTimestamp, endTime, zoneId, maxMessages, false, false, null, null);
    }

    public ChatLogsResult fetchRoomHistoryDetailed(String roomId, int hours, String fromToken, long startTimestamp,
            long endTime, ZoneId zoneId, int maxMessages, java.util.concurrent.atomic.AtomicBoolean abortFlag) {
        return fetchRoomHistoryDetailed(roomId, hours, fromToken, startTimestamp, endTime, zoneId, maxMessages, false, false, abortFlag, null);
    }

    public ChatLogsResult fetchRoomHistoryDetailed(String roomId, int hours, String fromToken, long startTimestamp,
            long endTime, ZoneId zoneId, int maxMessages, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            ProgressCallback progressCallback) {
        return fetchRoomHistoryDetailed(roomId, hours, fromToken, startTimestamp, endTime, zoneId, maxMessages, false, false, abortFlag, progressCallback);
    }

    // New method with image collection
    public ChatLogsResult fetchRoomHistoryDetailed(String roomId, int hours, String fromToken, long startTimestamp,
            long endTime, ZoneId zoneId, int maxMessages, boolean collectImages, boolean aiFriendlyTimestamps, java.util.concurrent.atomic.AtomicBoolean abortFlag,
            ProgressCallback progressCallback) {
        List<RawLogLine> rawLines = new ArrayList<>();
        List<String> imageUrls = collectImages ? new ArrayList<>() : null;
        List<String> imageCaptions = collectImages ? new ArrayList<>() : null;
        String firstEventId = null;

        long startTime = (startTimestamp > 0) ? startTimestamp
                : (hours > 0 ? System.currentTimeMillis() - (long) hours * 3600L * 1000L : -1);
        long calculatedEndTime = (endTime > 0) ? endTime : System.currentTimeMillis();

        String token = getPaginationToken(roomId, fromToken);

        while (token != null) {
            if (abortFlag != null && abortFlag.get()) {
                System.out.println("fetchRoomHistoryDetailed aborted.");
                break;
            }
            try {
                String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                        + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                HttpRequest msgReq = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl))
                        .header("Authorization", "Bearer " + accessToken)
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build();
                HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
                if (msgResp.statusCode() != 200) {
                    System.out.println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                    break;
                }
                JsonNode root = mapper.readTree(msgResp.body());
                JsonNode chunk = root.path("chunk");
                if (!chunk.isArray() || chunk.size() == 0)
                    break;

                boolean reachedStart = false;
                for (JsonNode ev : chunk) {
                    if (!"m.room.message".equals(ev.path("type").asText(null)))
                        continue;
                    long originServerTs = ev.path("origin_server_ts").asLong(0);

                    if (originServerTs > calculatedEndTime) {
                        continue;
                    }
                    if (startTime > 0 && originServerTs < startTime) {
                        reachedStart = true;
                        break;
                    }

                    String body = ev.path("content").path("body").asText(null);
                    String sender = ev.path("sender").asText(null);
                    String eventId = ev.path("event_id").asText(null);
                    String msgtype = ev.path("content").path("msgtype").asText(null);

                    if (body != null && sender != null) {
                        rawLines.add(new RawLogLine(originServerTs, sender, body));

                        firstEventId = eventId;

                        // Collect image URLs if enabled
                        if (collectImages && "m.image".equals(msgtype)) {
                            String imageUrl = ev.path("content").path("url").asText(null);
                            if (imageUrl != null && !imageUrl.isEmpty()) {
                                imageUrls.add(imageUrl);
                                imageCaptions.add(body); // body is the caption/filename
                            }
                        }

                        // Check if we've reached the requested message count
                        if (maxMessages > 0 && rawLines.size() >= maxMessages) {
                            reachedStart = true;
                            break;
                        }
                    }
                }

                // Report progress after each batch
                if (progressCallback != null && !rawLines.isEmpty()) {
                    progressCallback.onProgress(rawLines.size(), estimateFormattedTokens(rawLines, zoneId, aiFriendlyTimestamps));
                }

                if (reachedStart) {
                    break;
                }

                token = root.path("end").asText(null);

            } catch (Exception e) {
                System.out.println("Error fetching room history: " + e.getMessage());
                break;
            }
        }
        Collections.reverse(rawLines);
        if (collectImages) {
            Collections.reverse(imageUrls);
            Collections.reverse(imageCaptions);
        }
        return new ChatLogsResult(formatLogLines(rawLines, zoneId, aiFriendlyTimestamps), firstEventId, null, imageUrls, imageCaptions);
    }

    /**
     * Get the last message sent by a user in a room
     */
    public EventInfo getLastMessageFromSender(String roomId, String sender) {
        try {
            String token = getPaginationToken(roomId, null);
            if (token == null) {
                return null;
            }

            String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                    + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                    + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
            HttpRequest msgReq = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());

            if (msgResp.statusCode() != 200) {
                System.out.println("Failed to fetch messages for last message: " + msgResp.statusCode());
                return null;
            }

            JsonNode msgRoot = mapper.readTree(msgResp.body());
            JsonNode chunk = msgRoot.path("chunk");
            if (!chunk.isArray()) {
                return null;
            }

            for (JsonNode ev : chunk) {
                if (!"m.room.message".equals(ev.path("type").asText(null)))
                    continue;
                String msgSender = ev.path("sender").asText(null);
                if (sender.equals(msgSender)) {
                    return new EventInfo(ev.path("event_id").asText(null), ev.path("origin_server_ts").asLong(0));
                }
            }

            // Try next page if not found
            String endToken = msgRoot.path("end").asText(null);
            if (endToken != null) {
                String messagesUrl2 = homeserverUrl + "/_matrix/client/v3/rooms/"
                        + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(endToken, StandardCharsets.UTF_8) + "&dir=b&limit=1000";
                HttpRequest msgReq2 = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl2))
                        .header("Authorization", "Bearer " + accessToken)
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build();
                HttpResponse<String> msgResp2 = httpClient.send(msgReq2, HttpResponse.BodyHandlers.ofString());

                if (msgResp2.statusCode() == 200) {
                    JsonNode msgRoot2 = mapper.readTree(msgResp2.body());
                    JsonNode chunk2 = msgRoot2.path("chunk");
                    if (chunk2.isArray()) {
                        for (JsonNode ev : chunk2) {
                            if (!"m.room.message".equals(ev.path("type").asText(null)))
                                continue;
                            String msgSender = ev.path("sender").asText(null);
                            if (sender.equals(msgSender)) {
                                return new EventInfo(ev.path("event_id").asText(null),
                                        ev.path("origin_server_ts").asLong(0));
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

    /**
     * Count unread messages in a room from lastReadEventId to the latest message.
     */
    public int countUnreadMessages(String roomId, String lastReadEventId) {
        if (lastReadEventId == null)
            return -1;
        try {
            String token = getPaginationToken(roomId, null);
            if (token == null)
                return -1;

            int count = 0;
            boolean foundLastRead = false;

            while (token != null && !foundLastRead) {
                String url = homeserverUrl + "/_matrix/client/v3/rooms/"
                        + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=100";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200)
                    break;

                JsonNode root = mapper.readTree(resp.body());
                JsonNode chunk = root.path("chunk");
                if (!chunk.isArray() || chunk.size() == 0)
                    break;

                for (JsonNode ev : chunk) {
                    String eventId = ev.path("event_id").asText("");
                    if (eventId.equals(lastReadEventId)) {
                        foundLastRead = true;
                        break;
                    }
                    if ("m.room.message".equals(ev.path("type").asText(null))) {
                        count++;
                    }
                }

                if (foundLastRead || count > 1000)
                    break; // Limit search
                token = root.path("end").asText(null);
            }

            return foundLastRead ? count : -1;
        } catch (Exception e) {
            System.err.println("Error counting unread messages: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Fetch all messages in a room from lastReadEventId to the latest message.
     */
    public ChatLogsResult fetchUnreadMessages(String roomId, String lastReadEventId, ZoneId zoneId) {
        return fetchUnreadMessages(roomId, lastReadEventId, zoneId, false, null, null);
    }

    public ChatLogsResult fetchUnreadMessages(String roomId, String lastReadEventId, ZoneId zoneId, java.util.concurrent.atomic.AtomicBoolean abortFlag) {
        return fetchUnreadMessages(roomId, lastReadEventId, zoneId, false, abortFlag, null);
    }

    public ChatLogsResult fetchUnreadMessages(String roomId, String lastReadEventId, ZoneId zoneId,
            java.util.concurrent.atomic.AtomicBoolean abortFlag, ProgressCallback progressCallback) {
        return fetchUnreadMessages(roomId, lastReadEventId, zoneId, false, abortFlag, progressCallback);
    }

    public ChatLogsResult fetchUnreadMessages(String roomId, String lastReadEventId, ZoneId zoneId,
            boolean aiFriendlyTimestamps, java.util.concurrent.atomic.AtomicBoolean abortFlag, ProgressCallback progressCallback) {
        if (lastReadEventId == null)
            return new ChatLogsResult(new ArrayList<>(), null);

        List<RawLogLine> rawLines = new ArrayList<>();
        String firstEventId = null;

        try {
            String token = getPaginationToken(roomId, null);
            if (token == null)
                return new ChatLogsResult(formatLogLines(rawLines, zoneId, aiFriendlyTimestamps), null);

            boolean foundLastRead = false;

            while (token != null && !foundLastRead) {
                if (abortFlag != null && abortFlag.get()) {
                    System.out.println("fetchUnreadMessages aborted.");
                    break;
                }
                String url = homeserverUrl + "/_matrix/client/v3/rooms/"
                        + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=100";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200)
                    break;

                JsonNode root = mapper.readTree(resp.body());
                JsonNode chunk = root.path("chunk");
                if (!chunk.isArray() || chunk.size() == 0)
                    break;

                for (JsonNode ev : chunk) {
                    String eventId = ev.path("event_id").asText("");
                    if (eventId.equals(lastReadEventId)) {
                        foundLastRead = true;
                        break;
                    }
                    if ("m.room.message".equals(ev.path("type").asText(null))) {
                        String body = ev.path("content").path("body").asText(null);
                        String sender = ev.path("sender").asText(null);
                        long originServerTs = ev.path("origin_server_ts").asLong(0);

                        if (body != null && sender != null) {
                            rawLines.add(new RawLogLine(originServerTs, sender, body));
                            firstEventId = eventId;
                        }
                    }
                }

                // Report progress after each batch
                if (progressCallback != null && !rawLines.isEmpty()) {
                    progressCallback.onProgress(rawLines.size(), estimateFormattedTokens(rawLines, zoneId, aiFriendlyTimestamps));
                }

                if (foundLastRead || rawLines.size() > 500)
                    break; // Safety limit
                token = root.path("end").asText(null);
            }

            Collections.reverse(rawLines);
            return new ChatLogsResult(formatLogLines(rawLines, zoneId, aiFriendlyTimestamps), firstEventId);
        } catch (Exception e) {
            System.err.println("Error fetching unread messages: " + e.getMessage());
            return new ChatLogsResult(formatLogLines(rawLines, zoneId, aiFriendlyTimestamps), null);
        }
    }

/**
     * Estimates the number of LLM tokens a string will use.
     * Counts punctuation as 1 token each, and accounts for BPE subword tokenization
     * by estimating longer words (>4 chars) as multiple tokens.
     * Adds a 5% safety margin.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        int wordLen = 0;
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (wordLen > 0) {
                    // Short words (1-4 chars) = 1 token; longer words get extra subword tokens
                    count += 1 + Math.max(0, (wordLen - 4) / 4);
                    wordLen = 0;
                }
            } else if (Character.isLetterOrDigit(c)) {
                wordLen++;
            } else {
                if (wordLen > 0) {
                    count += 1 + Math.max(0, (wordLen - 4) / 4);
                    wordLen = 0;
                }
                // Punctuation, symbols, and special characters = 1 token each
                count++;
            }
        }
        // Handle trailing word
        if (wordLen > 0) {
            count += 1 + Math.max(0, (wordLen - 4) / 4);
        }
        // 5% safety margin + 1 for line structure tokens
        return (int) Math.ceil(count * 1.05) + 1; 
    }

    /**
     * Fetch room history backwards until a token limit is reached.
     */
    public ChatLogsResult fetchRoomHistoryUntilLimit(String roomId, String fromToken, int tokenLimit, boolean includeTimestamp, ZoneId zoneId) {
        return fetchRoomHistoryUntilLimit(roomId, fromToken, tokenLimit, includeTimestamp, zoneId, false, null, null);
    }

    public ChatLogsResult fetchRoomHistoryUntilLimit(String roomId, String fromToken, int tokenLimit, boolean includeTimestamp, ZoneId zoneId, java.util.concurrent.atomic.AtomicBoolean abortFlag) {
        return fetchRoomHistoryUntilLimit(roomId, fromToken, tokenLimit, includeTimestamp, zoneId, false, abortFlag, null);
    }

    public ChatLogsResult fetchRoomHistoryUntilLimit(String roomId, String fromToken, int tokenLimit, boolean includeTimestamp, ZoneId zoneId,
            java.util.concurrent.atomic.AtomicBoolean abortFlag, ProgressCallback progressCallback) {
        return fetchRoomHistoryUntilLimit(roomId, fromToken, tokenLimit, includeTimestamp, zoneId, false, abortFlag, progressCallback);
    }

    public ChatLogsResult fetchRoomHistoryUntilLimit(String roomId, String fromToken, int tokenLimit, boolean includeTimestamp, ZoneId zoneId,
            boolean aiFriendlyTimestamps, java.util.concurrent.atomic.AtomicBoolean abortFlag, ProgressCallback progressCallback) {
        List<String> logs = new ArrayList<>();
        List<RawLogLine> rawLines = includeTimestamp ? new ArrayList<>() : null;
        String firstEventId = null;
        int currentTokens = 0;

        String token = getPaginationToken(roomId, fromToken);

        while (token != null && currentTokens < tokenLimit) {
            if (abortFlag != null && abortFlag.get()) {
                System.out.println("fetchRoomHistoryUntilLimit aborted.");
                break;
            }
            try {
                String messagesUrl = homeserverUrl + "/_matrix/client/v3/rooms/"
                        + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                        + "/messages?from=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&dir=b&limit=100";
                HttpRequest msgReq = HttpRequest.newBuilder()
                        .uri(URI.create(messagesUrl))
                        .header("Authorization", "Bearer " + accessToken)
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build();
                HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
                if (msgResp.statusCode() != 200) {
                    System.out.println("Failed to fetch messages: " + msgResp.statusCode() + " - " + msgResp.body());
                    break;
                }
                JsonNode root = mapper.readTree(msgResp.body());
                JsonNode chunk = root.path("chunk");
                if (!chunk.isArray() || chunk.size() == 0)
                    break;

                boolean reachedLimit = false;
                for (JsonNode ev : chunk) {
                    if (!"m.room.message".equals(ev.path("type").asText(null)))
                        continue;

                    String body = ev.path("content").path("body").asText(null);
                    String sender = ev.path("sender").asText(null);
                    String eventId = ev.path("event_id").asText(null);
                    if (body != null && sender != null) {
                        String line;
                        if (includeTimestamp) {
                            long originServerTs = ev.path("origin_server_ts").asLong(0);
                            RawLogLine rawLine = new RawLogLine(originServerTs, sender, body);
                            rawLines.add(rawLine);
                            line = formatLogLine(rawLine, zoneId, null, aiFriendlyTimestamps);
                        } else {
                            line = "<" + sender + "> " + body;
                        }

                        int lineTokens = estimateTokens(line);

                        if (currentTokens + lineTokens > tokenLimit) {
                            if (includeTimestamp) {
                                rawLines.remove(rawLines.size() - 1);
                            }
                            reachedLimit = true;
                            break;
                        }

                        logs.add(line);
                        currentTokens += lineTokens;
                        firstEventId = eventId;
                    }
                }

                // Report progress after each batch
                int gatheredCount = includeTimestamp ? rawLines.size() : logs.size();
                if (progressCallback != null && gatheredCount > 0) {
                    progressCallback.onProgress(gatheredCount, currentTokens);
                }

                if (reachedLimit) {
                    break;
                }

                token = root.path("end").asText(null);

            } catch (Exception e) {
                System.out.println("Error fetching room history until limit: " + e.getMessage());
                break;
            }
        }
        if (includeTimestamp) {
            Collections.reverse(rawLines);
            return new ChatLogsResult(formatLogLines(rawLines, zoneId, aiFriendlyTimestamps), firstEventId);
        }

        Collections.reverse(logs);
        return new ChatLogsResult(logs, firstEventId);
    }

    /**
     * Get read receipt for a user in a room
     */
    public EventInfo getReadReceipt(String roomId, String userId) {
        try {
            Map<Long, List<String>> receiptsWithTimestamps = new TreeMap<>(Collections.reverseOrder());

            // Try to get the read receipt from the sync response first
            // Use lightweight filter to avoid 504 timeouts
            String filter = "{\"room\":{\"rooms\":[\"" + roomId + "\"],\"timeline\":{\"limit\":1},\"state\":{\"lazy_load_members\":true},\"ephemeral\":{\"limit\":1}},\"presence\":{\"not_types\":[\"m.presence\"]}}";
            String syncUrl = homeserverUrl + "/_matrix/client/v3/sync?timeout=0&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8);
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
                                Iterator<String> eventIds = content.fieldNames();
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

                                        receiptsWithTimestamps
                                                .computeIfAbsent(timestamp, k -> new ArrayList<>())
                                                .add(eventId);
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
            String accountDataUrl = homeserverUrl + "/_matrix/client/v3/user/" + encodedUser + "/rooms/" + encodedRoom
                    + "/account_data/m.read";
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
                    receiptsWithTimestamps.computeIfAbsent(accountDataTimestamp, k -> new ArrayList<>())
                            .add(lastRead);
                }
            }

            if (!receiptsWithTimestamps.isEmpty()) {
                Map.Entry<Long, List<String>> firstEntry = receiptsWithTimestamps.entrySet().iterator()
                        .next();
                long ts = firstEntry.getKey();
                if (ts == Long.MAX_VALUE - 1 || ts < 1000000000000L)
                    ts = 0; // Ignore fake hash-based or sentinel timestamps
                return new EventInfo(firstEntry.getValue().get(firstEntry.getValue().size() - 1),
                        ts);
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
    public boolean isLatestMessage(String roomId, String eventId) {
        try {
            // Use lightweight filter to avoid 504 timeouts
            String filter = "{\"room\":{\"rooms\":[\"" + roomId + "\"],\"timeline\":{\"limit\":1},\"state\":{\"lazy_load_members\":true}},\"presence\":{\"not_types\":[\"m.presence\"]}}";
            String syncUrl = homeserverUrl + "/_matrix/client/v3/sync?timeout=0&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8);
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

    /**
     * Get pagination token from sync response
     */
    private String getPaginationToken(String roomId, String providedToken) {
        if (providedToken != null) {
            return providedToken;
        }

        try {
            // Use lightweight filter to avoid 504 timeouts
            String filter = "{\"room\":{\"rooms\":[\"" + roomId + "\"],\"timeline\":{\"limit\":1},\"state\":{\"lazy_load_members\":true}},\"presence\":{\"not_types\":[\"m.presence\"]}}";
            String syncUrl = homeserverUrl + "/_matrix/client/v3/sync?timeout=0&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8);
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
                    return roomNode.path("timeline").path("prev_batch").asText(null);
                }
            }
        } catch (Exception ignore) {
            // ignore
        }
        return null;
    }
}
