package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-free semantic search engine using local text similarity
 * Uses Jaccard similarity and word overlap for ranking
 */

public class SemanticSearchService {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String homeserver;
    private final String accessToken;
    private final RoomHistoryManager historyManager;

    public SemanticSearchService(HttpClient client, ObjectMapper mapper, String homeserver, String accessToken) {
        this.client = client;
        this.mapper = mapper;
        this.homeserver = homeserver;
        this.accessToken = accessToken;
        this.historyManager = new RoomHistoryManager(client, mapper, homeserver, accessToken);
    }

    public static class MessageEmbedding {
        public String eventId;
        public String message;
        public String timestamp;
        public String sender;
        public double[] embedding;

        public MessageEmbedding(String eventId, String message, String timestamp, String sender, double[] embedding) {
            this.eventId = eventId;
            this.message = message;
            this.timestamp = timestamp;
            this.sender = sender;
            this.embedding = embedding;
        }
    }

    public void performSemanticSearch(String responseRoomId, String exportRoomId, int hours, String fromToken,
            String query, ZoneId zoneId) {
        MatrixClient matrixClient = new MatrixClient(client, mapper, homeserver, accessToken);
        try {
            String timeInfo = "last " + hours + "h";

            matrixClient.sendText(responseRoomId,
                    "Performing semantic search in " + exportRoomId + " for: \"" + query + "\" (" + timeInfo + ")...");

            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            RoomHistoryManager.ChatLogsWithIds result = historyManager.fetchRoomHistoryWithIds(exportRoomId, hours,
                    fromToken, startTime, endTime, zoneId);

            if (result.logs.isEmpty()) {
                matrixClient.sendText(responseRoomId,
                        "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            List<MessageEmbedding> embeddings = new ArrayList<>();
            Pattern pattern = Pattern.compile("\\[(.*?)\\] <(.*?)> (.*)");

            for (int i = 0; i < result.logs.size(); i++) {
                String log = result.logs.get(i);
                String eventId = result.eventIds.get(i);
                Matcher matcher = pattern.matcher(log);
                if (matcher.matches()) {
                    String timestamp = matcher.group(1);
                    String sender = matcher.group(2);
                    String message = matcher.group(3);

                    embeddings.add(new MessageEmbedding(eventId, message, timestamp, sender, new double[0]));
                }
            }

            List<MessageEmbedding> searchResults = search(query, embeddings, 5);

            if (searchResults.isEmpty()) {
                matrixClient.sendText(responseRoomId, "No relevant messages found for query: \"" + query + "\"");
                return;
            }

            StringBuilder response = new StringBuilder();
            response.append("Semantic Search Results\n\n");
            response.append("Query: \"").append(query).append("\"\n");
            response.append("Time range: last ").append(hours).append(" hours\n\n");

            for (MessageEmbedding resultMsg : searchResults) {
                double similarity = resultMsg.embedding.length > 0 ? resultMsg.embedding[0] : 0.0;
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + resultMsg.eventId;

                response.append("[").append(resultMsg.timestamp).append("] <").append(resultMsg.sender)
                        .append("> (score: ").append(String.format("%.2f", similarity)).append(")\n");
                response.append(resultMsg.message).append("\n");
                response.append(messageLink).append("\n\n");
            }

            matrixClient.sendText(responseRoomId, response.toString());

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendText(responseRoomId, "Error performing semantic search: " + e.getMessage());
        }
    }

    private static double calculateTextSimilarity(String query, String message) {
        String[] queryWords = query.toLowerCase().split("\\W+");
        String[] messageWords = message.toLowerCase().split("\\W+");

        Set<String> querySet = new HashSet<>();
        for (String word : queryWords) {
            if (word.length() > 2)
                querySet.add(word);
        }

        Set<String> messageSet = new HashSet<>();
        for (String word : messageWords) {
            if (word.length() > 2)
                messageSet.add(word);
        }

        if (querySet.isEmpty() || messageSet.isEmpty())
            return 0.0;

        Set<String> intersection = new HashSet<>(querySet);
        intersection.retainAll(messageSet);

        Set<String> union = new HashSet<>(querySet);
        union.addAll(messageSet);

        return (double) intersection.size() / union.size();
    }

    private static List<MessageEmbedding> search(String query, List<MessageEmbedding> embeddings, int topK) {
        if (embeddings.isEmpty())
            return new ArrayList<>();

        List<MessageEmbedding> results = new ArrayList<>();

        for (MessageEmbedding embedding : embeddings) {
            double similarity = calculateTextSimilarity(query, embedding.message);
            if (similarity > 0.1) {
                results.add(new MessageEmbedding(
                        embedding.eventId,
                        embedding.message,
                        embedding.timestamp,
                        embedding.sender,
                        new double[] { similarity }));
            }
        }

        results.sort((a, b) -> Double.compare(b.embedding[0], a.embedding[0]));

        return results.subList(0, Math.min(topK, results.size()));
    }
}
