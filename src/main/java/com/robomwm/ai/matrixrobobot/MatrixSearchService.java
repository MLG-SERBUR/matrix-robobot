package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles Matrix protocol native search using the /_matrix/client/v3/search API
 */
public class MatrixSearchService {
    private final MatrixClient matrixClient;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String homeserverUrl;
    private final String accessToken;
    private final Map<String, AtomicBoolean> runningOperations;

    public MatrixSearchService(MatrixClient matrixClient, HttpClient httpClient, ObjectMapper mapper,
            String homeserverUrl, String accessToken, Map<String, AtomicBoolean> runningOperations) {
        this.matrixClient = matrixClient;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
        this.runningOperations = runningOperations;
    }

    /**
     * Perform a Matrix native search using the server's search API
     */
    public void performMatrixSearch(String roomId, String sender, String responseRoomId, String searchRoomId,
            String query, ZoneId zoneId, AtomicBoolean abortFlag) {
        try {
            String initialMessage = "Searching Matrix for: \"" + query + "\" in " + searchRoomId + "...";
            String eventMessageId = matrixClient.sendTextWithEventId(responseRoomId, initialMessage);
            String originalEventId = eventMessageId;

            List<String> results = new ArrayList<>();
            List<String> eventIds = new ArrayList<>();
            Set<String> seenEventIds = new HashSet<>();
            int maxResults = 25;
            String nextBatch = null;
            int iteration = 0;
            int maxIterations = 5; // Prevent infinite loops

            System.out.println("Starting Matrix search for '" + query + "' in room " + searchRoomId);

            do {
                iteration++;
                System.out.println("Matrix search iteration " + iteration + ", results so far: " + results.size() + ", nextBatch: " + nextBatch);
                
                if (abortFlag.get()) {
                    System.out.println("Matrix search aborted by user: " + sender);
                    matrixClient.updateTextMessage(responseRoomId, originalEventId, "Matrix search aborted.");
                    return;
                }

                // Build the search request body
                Map<String, Object> searchBody = buildSearchRequest(searchRoomId, query, nextBatch);
                String json = mapper.writeValueAsString(searchBody);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(homeserverUrl + "/_matrix/client/v3/search"))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(30)) // Reduced timeout
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                System.out.println("Sending Matrix search request...");
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println("Matrix search response status: " + response.statusCode());

                if (response.statusCode() != 200) {
                    System.out.println("Matrix search failed: " + response.statusCode() + " - " + response.body());
                    matrixClient.updateTextMessage(responseRoomId, originalEventId,
                            "Matrix search failed with status: " + response.statusCode());
                    return;
                }

                JsonNode root = mapper.readTree(response.body());
                JsonNode searchCategories = root.path("search_categories");
                JsonNode roomEvents = searchCategories.path("room_events");
                JsonNode resultsArray = roomEvents.path("results");

                System.out.println("Matrix search returned " + resultsArray.size() + " results in this batch");

                if (!resultsArray.isArray() || resultsArray.size() == 0) {
                    System.out.println("No more results, exiting search loop");
                    break;
                }

                int addedCount = 0;
                for (JsonNode result : resultsArray) {
                    if (abortFlag.get()) {
                        System.out.println("Matrix search aborted by user: " + sender);
                        return;
                    }

                    if (results.size() >= maxResults) {
                        break;
                    }

                    JsonNode resultObj = result.path("result");
                    String eventId = resultObj.path("event_id").asText(null);
                    String eventSender = resultObj.path("sender").asText(null);
                    long originServerTs = resultObj.path("origin_server_ts").asLong(0);
                    String body = resultObj.path("content").path("body").asText(null);

                    if (eventId != null && eventSender != null && body != null) {
                        // Skip duplicate events (Matrix spec recommends deduplication by event ID)
                        if (!seenEventIds.add(eventId)) {
                            continue;
                        }
                        
                        String timestamp = java.time.Instant.ofEpochMilli(originServerTs)
                                .atZone(zoneId)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                        String formattedResult = "[" + timestamp + "] <" + eventSender + "> " + body;

                        results.add(formattedResult);
                        eventIds.add(eventId);
                        addedCount++;
                    }
                }
                
                System.out.println("Added " + addedCount + " new unique results in this iteration");

                // Check for next batch
                nextBatch = roomEvents.path("next_batch").asText(null);
                
                // Break if we got no new results in this iteration (avoids infinite loops)
                if (addedCount == 0) {
                    System.out.println("No new results in this iteration, exiting search loop");
                    break;
                }

            } while (nextBatch != null && results.size() < maxResults && iteration < maxIterations);
            
            System.out.println("Matrix search completed with " + results.size() + " total results");

            if (results.isEmpty()) {
                matrixClient.updateTextMessage(responseRoomId, originalEventId,
                        "No Matrix search results found for: \"" + query + "\" in " + searchRoomId + ".");
                return;
            }

            // Final update
            StringBuilder finalMsg = new StringBuilder();
            finalMsg.append("Matrix search results for \"").append(query).append("\" in ").append(searchRoomId)
                    .append(".\n");
            finalMsg.append(results.size()).append(" matches.\n\n");

            for (int i = 0; i < results.size(); i++) {
                finalMsg.append(results.get(i)).append(" ");
                String messageLink = "https://matrix.to/#/" + searchRoomId + "/" + eventIds.get(i);
                finalMsg.append(messageLink).append("\n");
            }

            matrixClient.updateTextMessage(responseRoomId, originalEventId, finalMsg.toString());

        } catch (Exception e) {
            System.out.println("Failed to perform Matrix search: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Error performing Matrix search: " + e.getMessage());
        }
    }

    /**
     * Build the Matrix search API request body
     */
    private Map<String, Object> buildSearchRequest(String roomId, String query, String nextBatch) {
        Map<String, Object> searchBody = new HashMap<>();

        Map<String, Object> roomEvents = new HashMap<>();
        roomEvents.put("search_term", query);
        roomEvents.put("keys", List.of("content.body", "sender"));
        roomEvents.put("order_by", "recent");
        roomEvents.put("limit", 25);

        Map<String, Object> filter = new HashMap<>();
        filter.put("rooms", List.of(roomId));
        roomEvents.put("filter", filter);

        if (nextBatch != null) {
            roomEvents.put("next_batch", nextBatch);
        }

        Map<String, Object> searchCategories = new HashMap<>();
        searchCategories.put("room_events", roomEvents);

        searchBody.put("search_categories", searchCategories);

        return searchBody;
    }
}