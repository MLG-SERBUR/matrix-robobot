package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
                    "Performing local semantic search in " + exportRoomId + " for: \"" + query + "\" (" + timeInfo
                            + ")...");

            long startTime = System.currentTimeMillis() - (long) hours * 3600L * 1000L;
            long endTime = System.currentTimeMillis();

            RoomHistoryManager.ChatLogsWithIds result = historyManager.fetchRoomHistoryWithIds(exportRoomId, hours,
                    fromToken, startTime, endTime, zoneId);

            if (result.logs.isEmpty()) {
                matrixClient.sendText(responseRoomId,
                        "No chat logs found for " + timeInfo + " in " + exportRoomId + ".");
                return;
            }

            List<MessageEmbedding> candidates = new ArrayList<>();
            Pattern pattern = Pattern.compile("\\[(.*?)\\] <(.*?)> (.*)");

            for (int i = 0; i < result.logs.size(); i++) {
                String log = result.logs.get(i);
                String eventId = result.eventIds.get(i);
                Matcher matcher = pattern.matcher(log);
                if (matcher.matches()) {
                    String timestamp = matcher.group(1);
                    String sender = matcher.group(2);
                    String message = matcher.group(3);
                    candidates.add(new MessageEmbedding(eventId, message, timestamp, sender, new double[0]));
                }
            }

            List<MessageEmbedding> searchResults = localSearch(query, candidates, 5);

            if (searchResults.isEmpty()) {
                matrixClient.sendText(responseRoomId, "No relevant matches found for query: \"" + query + "\"");
                return;
            }

            StringBuilder response = new StringBuilder();
            response.append("### Local Semantic Search Results\n\n");
            response.append("**Query:** \"").append(query).append("\"\n");
            response.append("**Time range:** last ").append(hours).append(" hours\n\n");

            for (MessageEmbedding resultMsg : searchResults) {
                double score = resultMsg.embedding.length > 0 ? resultMsg.embedding[0] : 0.0;
                String messageLink = "https://matrix.to/#/" + exportRoomId + "/" + resultMsg.eventId;

                response.append("[").append(resultMsg.timestamp).append("] <").append(resultMsg.sender)
                        .append("> (score: ").append(String.format("%.2f", score)).append(")\n");
                response.append(resultMsg.message).append("\n");
                response.append(messageLink).append("\n\n");
            }

            matrixClient.sendMarkdown(responseRoomId, response.toString());

        } catch (Exception e) {
            e.printStackTrace();
            matrixClient.sendText(responseRoomId, "Error performing local semantic search: " + e.getMessage());
        }
    }

    private List<MessageEmbedding> localSearch(String query, List<MessageEmbedding> candidates, int topK) {
        String[] queryTerms = tokenize(query);
        if (queryTerms.length == 0)
            return new ArrayList<>();

        // 1. Calculate Document Frequency (DF) for TF-IDF
        java.util.Map<String, Integer> dfMap = new java.util.HashMap<>();
        for (MessageEmbedding c : candidates) {
            Set<String> uniqueWords = new HashSet<>(java.util.Arrays.asList(tokenize(c.message)));
            for (String word : uniqueWords) {
                dfMap.put(word, dfMap.getOrDefault(word, 0) + 1);
            }
        }

        List<MessageEmbedding> scoredResults = new ArrayList<>();
        double n = candidates.size();

        for (MessageEmbedding c : candidates) {
            String[] docTerms = tokenize(c.message);
            if (docTerms.length == 0)
                continue;

            Set<String> docTermSet = new HashSet<>(java.util.Arrays.asList(docTerms));
            double tfIdfScore = 0;
            int matches = 0;

            for (String qt : queryTerms) {
                // TF: term frequency in doc
                double tf = 0;
                for (String dt : docTerms) {
                    if (dt.equals(qt))
                        tf++;
                }

                if (tf > 0) {
                    matches++;
                    // IDF: log(N/DF)
                    double idf = Math.log(n / (double) dfMap.getOrDefault(qt, 1) + 1.0);
                    tfIdfScore += tf * idf;
                }
            }

            if (matches == 0)
                continue;

            // 2. Proximity Scoring
            double proximityScore = calculateProximityScore(queryTerms, docTerms);

            // Final score: combine TF-IDF and proximity (proximity is a boost)
            double finalScore = (tfIdfScore * (1.0 + proximityScore)) / (double) queryTerms.length;

            scoredResults.add(new MessageEmbedding(
                    c.eventId, c.message, c.timestamp, c.sender, new double[] { finalScore }));
        }

        scoredResults.sort((a, b) -> Double.compare(b.embedding[0], a.embedding[0]));
        return scoredResults.subList(0, Math.min(topK, scoredResults.size()));
    }

    private double calculateProximityScore(String[] queryTerms, String[] docTerms) {
        if (queryTerms.length < 2)
            return 0.0;

        // Find positions of all query terms in the doc
        java.util.Map<String, List<Integer>> positions = new java.util.HashMap<>();
        for (int i = 0; i < docTerms.length; i++) {
            for (String qt : queryTerms) {
                if (docTerms[i].equals(qt)) {
                    positions.computeIfAbsent(qt, k -> new ArrayList<>()).add(i);
                }
            }
        }

        if (positions.size() < 2)
            return 0.0;

        // Simple proximity: inverse of distance between adjacent query terms
        double maxProximity = 0;
        for (int i = 0; i < queryTerms.length - 1; i++) {
            List<Integer> pos1 = positions.get(queryTerms[i]);
            List<Integer> pos2 = positions.get(queryTerms[i + 1]);

            if (pos1 == null || pos2 == null)
                continue;

            for (int p1 : pos1) {
                for (int p2 : pos2) {
                    int dist = Math.abs(p1 - p2);
                    if (dist > 0) {
                        maxProximity += 1.0 / (double) dist;
                    }
                }
            }
        }

        return Math.min(1.0, maxProximity); // Cap proximity boost at 1.0
    }

    private static String[] tokenize(String text) {
        if (text == null)
            return new String[0];
        String normalized = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
        String[] words = normalized.split("\\s+");
        List<String> filtered = new ArrayList<>();
        Set<String> stopWords = new HashSet<>(java.util.Arrays.asList(
                "the", "is", "at", "which", "on", "and", "a", "an", "of", "to", "in", "it", "for", "with", "as"));

        for (String w : words) {
            if (w.length() > 2 && !stopWords.contains(w)) {
                filtered.add(w);
            }
        }
        return filtered.toArray(new String[0]);
    }
}
