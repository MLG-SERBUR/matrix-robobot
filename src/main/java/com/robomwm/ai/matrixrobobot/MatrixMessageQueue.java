package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import java.net.http.HttpClient;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Global message queue for all Matrix message operations.
 * This ensures that messages are not lost due to temporary Matrix failures or rate limiting.
 * Messages are queued and flushed at regular intervals to avoid rate limiting.
 */
public class MatrixMessageQueue {
    private static final long FLUSH_INTERVAL_MS = 5000; // 5 seconds between flushes
    private static final long MAX_QUEUE_AGE_MS = 30000; // 30 seconds max age before forced flush
    private static final int MAX_RETRIES = Integer.MAX_VALUE; // Always retry
    
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isRunning;
    
    // Queue structure: roomId -> List of QueuedMessage
    private final ConcurrentMap<String, List<QueuedMessage>> messageQueues;
    
    public static class QueuedMessage {
        final String roomId;
        final String message;
        final String msgType; // "m.notice" or "m.text" or "m.room.message"
        final boolean useMarkdown;
        final String originalEventId; // For updates, the event ID to replace
        final boolean isUpdate; // Whether this is an update to existing message
        int retryCount;
        final long createdTime;
        
        QueuedMessage(String roomId, String message, String msgType, boolean useMarkdown, 
                     String originalEventId, boolean isUpdate) {
            this.roomId = roomId;
            this.message = message;
            this.msgType = msgType;
            this.useMarkdown = useMarkdown;
            this.originalEventId = originalEventId;
            this.isUpdate = isUpdate;
            this.retryCount = 0;
            this.createdTime = System.currentTimeMillis();
        }
        
        boolean shouldForceFlush() {
            return System.currentTimeMillis() - createdTime > MAX_QUEUE_AGE_MS;
        }
    }
    
    // Singleton instance
    private static MatrixMessageQueue instance;
    
    public static synchronized MatrixMessageQueue getInstance(HttpClient httpClient, ObjectMapper mapper, 
                                                                String homeserverUrl, String accessToken) {
        if (instance == null || 
            instance.httpClient != httpClient || 
            instance.mapper != mapper || 
            !instance.homeserverUrl.equals(homeserverUrl) ||
            !instance.accessToken.equals(accessToken)) {
            if (instance != null) {
                instance.shutdown();
            }
            instance = new MatrixMessageQueue(httpClient, mapper, homeserverUrl, accessToken);
        }
        return instance;
    }
    
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }
    
    private MatrixMessageQueue(HttpClient httpClient, ObjectMapper mapper, 
                                 String homeserverUrl, String accessToken) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl.endsWith("/")
                ? homeserverUrl.substring(0, homeserverUrl.length() - 1)
                : homeserverUrl;
        this.accessToken = accessToken;
        this.messageQueues = new ConcurrentHashMap<>();
        this.isRunning = new AtomicBoolean(true);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MatrixMessageQueue-Flusher");
            t.setDaemon(true);
            return t;
        });
        
        // Schedule periodic flushing
        this.scheduler.scheduleAtFixedRate(this::flushAll, FLUSH_INTERVAL_MS, 
                                              FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Send a new notice message, queuing if necessary
     */
    public String sendNoticeWithEventId(String roomId, String message) {
        return sendMessage(roomId, message, "m.notice", false, null, false);
    }
    
    /**
     * Send a new text message, queuing if necessary
     */
    public String sendTextWithEventId(String roomId, String message) {
        return sendMessage(roomId, message, "m.text", false, null, false);
    }
    
    /**
     * Send a new markdown message, queuing if necessary
     */
    public String sendMarkdownWithEventId(String roomId, String message) {
        return sendMessage(roomId, message, "m.text", true, null, false);
    }
    
    /**
     * Send a new markdown notice message, queuing if necessary
     */
    public String sendMarkdownNoticeWithEventId(String roomId, String message) {
        return sendMessage(roomId, message, "m.notice", true, null, false);
    }
    
    /**
     * Update an existing notice message, queuing if necessary
     */
    public String updateNoticeMessage(String roomId, String eventId, String message) {
        return sendMessage(roomId, message, "m.notice", false, eventId, true);
    }
    
    /**
     * Update an existing text message, queuing if necessary
     */
    public String updateTextMessage(String roomId, String eventId, String message) {
        return sendMessage(roomId, message, "m.text", false, eventId, true);
    }
    
    /**
     * Update an existing markdown message, queuing if necessary
     */
    public String updateMarkdownMessage(String roomId, String eventId, String message) {
        return sendMessage(roomId, message, "m.text", true, eventId, true);
    }
    
    /**
     * Update an existing markdown notice message, queuing if necessary
     */
    public String updateMarkdownNoticeMessage(String roomId, String eventId, String message) {
        return sendMessage(roomId, message, "m.notice", true, eventId, true);
    }
    
    /**
     * Generic method to send or queue a message
     */
    private String sendMessage(String roomId, String message, String msgType, 
                               boolean useMarkdown, String originalEventId, boolean isUpdate) {
        // Try to send immediately first
        String result = trySendImmediately(roomId, message, msgType, useMarkdown, originalEventId, isUpdate);
        
        if (result != null && !result.isEmpty()) {
            return result; // Success
        }
        
        // If immediate send failed, queue the message
        QueuedMessage queuedMsg = new QueuedMessage(roomId, message, msgType, useMarkdown, originalEventId, isUpdate);
        queueMessage(queuedMsg);
        
        return null; // Indicate queued (not immediately sent)
    }
    
    /**
     * Sanitize user IDs in message content to prevent mentioning issues
     */
    private String sanitizeUserIds(String message) {
        return MatrixClient.sanitizeUserIdsStatic(message);
    }
    
    /**
     * Convert markdown to HTML (simplified version)
     */
    private String convertMarkdownToHtml(String markdown) {
        try {
            Parser parser = Parser.builder().build();
            org.commonmark.node.Node document = parser.parse(markdown);
            HtmlRenderer renderer = HtmlRenderer.builder().build();
            return renderer.render(document);
        } catch (Exception e) {
            return markdown; // Fallback to plain text
        }
    }
    
    /**
     * Try to send a message immediately to Matrix
     */
    private String trySendImmediately(String roomId, String message, String msgType, 
                                     boolean useMarkdown, String originalEventId, boolean isUpdate) {
        try {
            if (isUpdate && originalEventId != null && !originalEventId.isEmpty()) {
                return sendUpdateRequest(roomId, message, msgType, useMarkdown, originalEventId);
            } else {
                return sendNewMessageRequest(roomId, message, msgType, useMarkdown);
            }
        } catch (Exception e) {
            System.out.println("Matrix message send failed, queuing for retry: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Send a new message request to Matrix
     */
    private String sendNewMessageRequest(String roomId, String message, String msgType, boolean useMarkdown) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = java.net.URLEncoder.encode(roomId, java.nio.charset.StandardCharsets.UTF_8);
            String endpoint = homeserverUrl + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + txnId;

            String sanitizedMessage = sanitizeUserIds(message);

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("msgtype", msgType);
            payload.put("body", sanitizedMessage);
            if (useMarkdown) {
                payload.put("format", "org.matrix.custom.html");
                payload.put("formatted_body", convertMarkdownToHtml(sanitizedMessage));
            }
            payload.put("m.mentions", java.util.Map.of());
            String json = mapper.writeValueAsString(payload);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(120))
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.body());
                return root.path("event_id").asText(null);
            }
            System.out.println("Matrix send message -> " + response.statusCode());
            return null;
        } catch (Exception e) {
            System.out.println("Failed to send Matrix message: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Send an update message request to Matrix
     */
    private String sendUpdateRequest(String roomId, String message, String msgType, 
                                    boolean useMarkdown, String originalEventId) {
        try {
            String txnId = "m" + Instant.now().toEpochMilli();
            String encodedRoom = java.net.URLEncoder.encode(roomId, java.nio.charset.StandardCharsets.UTF_8);
            String endpoint = homeserverUrl + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + txnId;

            String sanitizedMessage = sanitizeUserIds(message);

            java.util.Map<String, Object> newContent = new java.util.HashMap<>();
            newContent.put("msgtype", msgType);
            newContent.put("body", sanitizedMessage);
            newContent.put("m.mentions", java.util.Map.of());
            
            if (useMarkdown) {
                String htmlBody = convertMarkdownToHtml(sanitizedMessage);
                newContent.put("format", "org.matrix.custom.html");
                newContent.put("formatted_body", htmlBody);
            }

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("msgtype", msgType);
            payload.put("body", "* " + sanitizedMessage);
            payload.put("m.mentions", java.util.Map.of());
            payload.put("m.new_content", newContent);

            java.util.Map<String, Object> relatesTo = new java.util.HashMap<>();
            relatesTo.put("event_id", originalEventId);
            relatesTo.put("rel_type", "m.replace");
            payload.put("m.relates_to", relatesTo);

            String json = mapper.writeValueAsString(payload);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(120))
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            System.out.println("Matrix update message " + originalEventId + " -> " + response.statusCode());

            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.body());
                return root.path("event_id").asText(null);
            }
            return null;
        } catch (Exception e) {
            System.out.println("Failed to update Matrix message " + originalEventId + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Add a message to the queue
     */
    private void queueMessage(QueuedMessage message) {
        messageQueues.computeIfAbsent(message.roomId, k -> new ArrayList<>()).add(message);
        System.out.println("Queued message for " + message.roomId + ": " + 
                          message.message.substring(0, Math.min(100, message.message.length())));
    }
    
    /**
     * Flush all queued messages
     */
    private void flushAll() {
        if (!isRunning.get()) return;
        
        for (String roomId : messageQueues.keySet()) {
            flushRoom(roomId);
        }
    }
    
    /**
     * Flush messages for a specific room
     */
    private void flushRoom(String roomId) {
        List<QueuedMessage> messages = messageQueues.get(roomId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        List<QueuedMessage> successfulMessages = new ArrayList<>();
        List<QueuedMessage> failedMessages = new ArrayList<>();
        
        for (QueuedMessage message : messages) {
            if (trySendWithRetry(message)) {
                successfulMessages.add(message);
            } else {
                // Check if message is too old and should be forced
                if (message.shouldForceFlush()) {
                    System.out.println("Forcing send of old message after " + 
                                      (System.currentTimeMillis() - message.createdTime) + "ms");
                    if (trySendWithRetry(message)) {
                        successfulMessages.add(message);
                    } else {
                        failedMessages.add(message);
                    }
                } else {
                    failedMessages.add(message);
                }
            }
        }
        
        // Update the queue: remove successful, keep failed
        if (!successfulMessages.isEmpty() || failedMessages.size() != messages.size()) {
            messageQueues.put(roomId, failedMessages);
        }
    }
    
    /**
     * Try to send a message with retry logic
     */
    private boolean trySendWithRetry(QueuedMessage message) {
        // Always retry - no limit
        String result = trySendImmediately(
            message.roomId, message.message, message.msgType, 
            message.useMarkdown, message.originalEventId, message.isUpdate
        );
        
        if (result != null && !result.isEmpty()) {
            System.out.println("Successfully sent queued message after " + message.retryCount + " retries");
            return true;
        }
        
        message.retryCount++;
        return false;
    }
    
    /**
     * Get the number of queued messages
     */
    public int getQueueSize() {
        int total = 0;
        for (List<QueuedMessage> messages : messageQueues.values()) {
            total += messages.size();
        }
        return total;
    }
    
    /**
     * Check if there are queued messages for a specific room
     */
    public boolean hasQueuedMessages(String roomId) {
        List<QueuedMessage> messages = messageQueues.get(roomId);
        return messages != null && !messages.isEmpty();
    }
    
    /**
     * Get the current event ID for a room's status (if any recent message was sent successfully)
     * This is a best-effort attempt for compatibility with existing code
     */
    public String getCurrentEventId(String roomId) {
        // We don't track event IDs in this queue, so return null
        // Existing code should handle null event IDs gracefully
        return null;
    }
    
    /**
     * Force immediate flush for testing or shutdown
     */
    public void forceFlush() {
        flushAll();
    }
    
    /**
     * Shutdown the queue
     */
    public void shutdown() {
        isRunning.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        messageQueues.clear();
    }
}