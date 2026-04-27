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
import java.util.Comparator;
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
    private final Map<String, SearchPaginationState> searchCache;

    public MatrixSearchService(MatrixClient matrixClient, HttpClient httpClient, ObjectMapper mapper,
            String homeserverUrl, String accessToken, Map<String, AtomicBoolean> runningOperations) {
        this.matrixClient = matrixClient;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
        this.runningOperations = runningOperations;
        this.searchCache = new java.util.concurrent.ConcurrentHashMap<>();
    }

    /**
     * Get the pagination state for a user. Returns null if not found.
     */
    public SearchPaginationState getSearchState(String sender) {
        return searchCache.get(sender);
    }

    /**
     * Navigate to next page for a user's search results
     */
    public boolean nextPage(String sender) {
        SearchPaginationState state = getSearchState(sender);
        if (state == null) return false;
        if (state.nextPage()) {
            matrixClient.updateNoticeMessage(state.getResponseRoomId(), state.getEventMessageId(),
                    state.renderPage());

            return true;
        }
        return false;
    }

    /**
     * Navigate to previous page for a user's search results
     */
    public boolean prevPage(String sender) {
        SearchPaginationState state = getSearchState(sender);
        if (state == null) return false;
        if (state.prevPage()) {
            matrixClient.updateNoticeMessage(state.getResponseRoomId(), state.getEventMessageId(),
                    state.renderPage());
            return true;
        }
        return false;
    }

    /**
     * Go to a specific page for a user's search results
     */
    public boolean goToPage(String sender, int pageNum) {
        SearchPaginationState state = getSearchState(sender);
        if (state == null) return false;
        if (state.goToPage(pageNum)) {
            matrixClient.updateNoticeMessage(state.getResponseRoomId(), state.getEventMessageId(),
                    state.renderPage());
            return true;
        }
        return false;
    }

    /**
     * Perform a Matrix native search using the server's search API
     */
    public void performMatrixSearch(String roomId, String sender, String responseRoomId, String searchRoomId,
            String query, int lookbackHours, ZoneId zoneId, AtomicBoolean abortFlag) {
        try {
            long cutoffTimestampMs = lookbackHours > 0
                    ? java.time.Instant.now().minus(java.time.Duration.ofHours(lookbackHours)).toEpochMilli()
                    : -1;
            String lookbackSuffix = lookbackHours > 0 ? " (last " + lookbackHours + "h)" : "";
            String initialMessage = "Searching Matrix for: \"" + query + "\" in " + searchRoomId + lookbackSuffix + "...";
            String eventMessageId = matrixClient.sendNoticeWithEventId(responseRoomId, initialMessage);

            List<SearchHit> hits = new ArrayList<>();
            Set<String> seenEventIds = new HashSet<>();

            System.out.println("Starting Matrix search for '" + query + "' in room " + searchRoomId);
            boolean searchFailed = fetchSearchResults(searchRoomId, query, sender, responseRoomId, eventMessageId,
                    abortFlag, hits, seenEventIds, cutoffTimestampMs);
            if (searchFailed || abortFlag.get()) {
                return;
            }

            hits.sort(Comparator.comparingLong(SearchHit::originServerTs).reversed());

            System.out.println("Matrix search completed with " + hits.size() + " total results");

            if (hits.isEmpty()) {
                matrixClient.updateTextMessage(responseRoomId, eventMessageId,
                        "No Matrix search results found for: \"" + query + "\" in " + searchRoomId + lookbackSuffix + ".");
                return;
            }

            // Create pagination state and cache it (replaces any existing search for this user)
            SearchPaginationState paginationState = new SearchPaginationState(hits, query, searchRoomId,
                    responseRoomId, eventMessageId, zoneId);
            searchCache.put(sender, paginationState);

            // Render first page
            matrixClient.updateNoticeMessage(responseRoomId, eventMessageId, paginationState.renderPage());

        } catch (Exception e) {
            System.out.println("Failed to perform Matrix search: " + e.getMessage());
            matrixClient.sendText(responseRoomId, "Error performing Matrix search: " + e.getMessage());
        }
    }

    private boolean fetchSearchResults(String searchRoomId, String query, String sender, String responseRoomId,
            String originalEventId, AtomicBoolean abortFlag, List<SearchHit> hits, Set<String> seenEventIds,
            long cutoffTimestampMs) throws Exception {
        String nextBatch = null;
        int iteration = 0;
        int maxIterations = 20; // Allow more iterations to fetch all results

        do {
            iteration++;
            System.out.println("Matrix search iteration " + iteration + " for query '" + query + "', results so far: "
                    + hits.size() + ", nextBatch: " + nextBatch);

            if (abortFlag.get()) {
                System.out.println("Matrix search aborted by user: " + sender);
                matrixClient.updateTextMessage(responseRoomId, originalEventId, "Matrix search aborted.");
                return true;
            }

            Map<String, Object> searchBody = buildSearchRequest(searchRoomId, query, nextBatch);
            String json = mapper.writeValueAsString(searchBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(homeserverUrl + "/_matrix/client/v3/search"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            System.out.println("Sending Matrix search request...");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Matrix search response status: " + response.statusCode());

            if (response.statusCode() != 200) {
                System.out.println("Matrix search failed: " + response.statusCode() + " - " + response.body());
                matrixClient.updateTextMessage(responseRoomId, originalEventId,
                        "Matrix search failed with status: " + response.statusCode());
                return true;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode roomEvents = root.path("search_categories").path("room_events");
            JsonNode resultsArray = roomEvents.path("results");

            System.out.println("Matrix search returned " + resultsArray.size() + " results in this batch");

            if (!resultsArray.isArray() || resultsArray.size() == 0) {
                System.out.println("No more results, exiting search loop");
                break;
            }

            int addedCount = 0;
            boolean reachedCutoff = false;
            for (JsonNode result : resultsArray) {
                if (abortFlag.get()) {
                    System.out.println("Matrix search aborted by user: " + sender);
                    matrixClient.updateTextMessage(responseRoomId, originalEventId, "Matrix search aborted.");
                    return true;
                }

                JsonNode resultObj = result.path("result");
                String eventId = resultObj.path("event_id").asText(null);
                String eventSender = resultObj.path("sender").asText(null);
                long originServerTs = resultObj.path("origin_server_ts").asLong(0);
                String body = resultObj.path("content").path("body").asText(null);

                if (cutoffTimestampMs > 0 && originServerTs > 0 && originServerTs < cutoffTimestampMs) {
                    reachedCutoff = true;
                    break;
                }

                if (eventId != null && eventSender != null && body != null && seenEventIds.add(eventId)) {
                    hits.add(new SearchHit(eventId, eventSender, body, originServerTs));
                    addedCount++;
                }
            }

            System.out.println("Added " + addedCount + " new unique results in this iteration");
            nextBatch = roomEvents.path("next_batch").asText(null);

            if (addedCount == 0) {
                System.out.println("No new results in this iteration, exiting search loop");
                break;
            }
            if (reachedCutoff) {
                System.out.println("Reached lookback cutoff for query '" + query + "', exiting search loop");
                break;
            }
        } while (nextBatch != null && iteration < maxIterations);

        return false;
    }

    private record SearchHit(String eventId, String sender, String body, long originServerTs) {
    }

    /**
     * Pagination state for search results
     */
    public static class SearchPaginationState {
        private final List<SearchHit> allHits;
        private int currentPage;
        private final int pageSize;
        private final String query;
        private final String searchRoomId;
        private final String responseRoomId;
        private final String eventMessageId;
        private final ZoneId zoneId;

        public SearchPaginationState(List<SearchHit> allHits, String query, String searchRoomId,
                String responseRoomId, String eventMessageId, ZoneId zoneId) {
            this.allHits = allHits;
            this.currentPage = 0;
            this.pageSize = 25;
            this.query = query;
            this.searchRoomId = searchRoomId;
            this.responseRoomId = responseRoomId;
            this.eventMessageId = eventMessageId;
            this.zoneId = zoneId;
        }

        public int getTotalPages() {
            return (int) Math.ceil((double) allHits.size() / pageSize);
        }

        public int getCurrentPage() {
            return currentPage;
        }

        public boolean hasNextPage() {
            return currentPage < getTotalPages() - 1;
        }

        public boolean hasPrevPage() {
            return currentPage > 0;
        }

        public List<SearchHit> getCurrentPageHits() {
            int fromIndex = currentPage * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, allHits.size());
            if (fromIndex >= allHits.size()) return List.of();
            return allHits.subList(fromIndex, toIndex);
        }

        public String renderPage() {
            List<SearchHit> pageHits = getCurrentPageHits();
            StringBuilder sb = new StringBuilder();
            sb.append("Matrix search results for \"").append(query).append("\" in ").append(searchRoomId).append(".\n");
            sb.append("Page ").append(currentPage + 1).append("/").append(getTotalPages())
                    .append(" (").append(allHits.size()).append(" total matches)\n\n");

            for (SearchHit hit : pageHits) {
                String timestamp = java.time.Instant.ofEpochMilli(hit.originServerTs())
                        .atZone(zoneId)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
                sb.append("[").append(timestamp).append("] <").append(hit.sender()).append("> ")
                        .append(hit.body()).append(" ");
                String messageLink = "https://matrix.to/#/" + searchRoomId + "/" + hit.eventId();
                sb.append(messageLink).append("\n");
            }

            sb.append("\n");
            if (getTotalPages() > 1) {
                sb.append("Use `!page <n>` to jump to a page (1-").append(getTotalPages()).append(").");
            }
            return sb.toString().trim();
        }

        public boolean nextPage() {
            if (hasNextPage()) {
                currentPage++;
                return true;
            }
            return false;
        }

        public boolean prevPage() {
            if (hasPrevPage()) {
                currentPage--;
                return true;
            }
            return false;
        }

        public boolean goToPage(int pageNum) {
            if (pageNum < 1 || pageNum > getTotalPages()) {
                return false;
            }
            currentPage = pageNum - 1;
            return true;
        }

        public String getResponseRoomId() {
            return responseRoomId;
        }

        public String getEventMessageId() {
            return eventMessageId;
        }
    }

    /**
     * Build the Matrix search API request body
     */
    private Map<String, Object> buildSearchRequest(String roomId, String query, String nextBatch) {
        Map<String, Object> searchBody = new HashMap<>();

        Map<String, Object> roomEvents = new HashMap<>();
        roomEvents.put("search_term", query);
        // Matrix search treats multiple keys as an AND clause, so including both
        // "content.body" and "sender" can unintentionally filter out all message
        // hits unless the query also appears in the sender MXID.
        roomEvents.put("keys", List.of("content.body"));
        roomEvents.put("order_by", "recent");
        roomEvents.put("limit", 25);

        Map<String, Object> filter = new HashMap<>();
        filter.put("rooms", List.of(roomId));
        roomEvents.put("filter", filter);

        Map<String, Object> searchCategories = new HashMap<>();
        searchCategories.put("room_events", roomEvents);

        searchBody.put("search_categories", searchCategories);

        if (nextBatch != null) {
            searchBody.put("next_batch", nextBatch);
        }

        return searchBody;
    }
}
