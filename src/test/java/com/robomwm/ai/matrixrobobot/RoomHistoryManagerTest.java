package com.robomwm.ai.matrixrobobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class RoomHistoryManagerTest {

    @Test
    void countBasedCommandUsesEpochWindow() {
        long before = System.currentTimeMillis();
        long[] window = CommandDispatcher.resolveHistoryWindowForCountRequest(-1, 25);
        long after = System.currentTimeMillis();

        assertEquals(0L, window[0]);
        assertTrue(window[1] >= before);
        assertTrue(window[1] <= after + 1000L);
    }

    @Test
    void durationCommandKeepsNegativeSentinel() {
        long[] window = CommandDispatcher.resolveHistoryWindowForCountRequest(6, -1);

        assertEquals(-1L, window[0]);
        assertEquals(-1L, window[1]);
    }

    @Test
    void countBasedHistoryIncludesRecentMessagesWithoutAnHourWindow() throws Exception {
        long eventTimestamp = System.currentTimeMillis() - 1000L;
        String responseBody = "{\"chunk\":[{\"type\":\"m.room.message\",\"origin_server_ts\":"
                + eventTimestamp + ",\"sender\":\"@user:example.org\",\"event_id\":\"$event\","
                + "\"content\":{\"msgtype\":\"m.text\",\"body\":\"recent message\"}}],\"end\":\"NEXT\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            RoomHistoryManager historyManager = new RoomHistoryManager(
                    HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-token");

            RoomHistoryManager.ChatLogsResult result = historyManager.fetchRoomHistoryRelative(
                    "!room:example.org", -1, "START", null, null, false, ZoneId.of("UTC"),
                    1, false, true, null, null);

            assertEquals(1, result.logs.size());
            assertTrue(result.logs.get(0).contains("recent message"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * The gather loop accumulates a running total per line instead of re-summing
     * the whole prompt (that was O(n^2) and, with a character-class estimator,
     * ~640ms per gather). The two must agree exactly, including across date
     * rollovers where the date prefix is printed once per day.
     */
    @Test
    void incrementalRunningTotalMatchesFullRecompute() {
        Random rnd = new Random(20261018L);
        long day = 86_400_000L;
        ZoneId zone = ZoneId.of("UTC");
        String[] models = {null, "openai/gpt-oss-120b", "qwen/qwen3.8-27b"};
        for (int trial = 0; trial < 500; trial++) {
            int n = 1 + rnd.nextInt(40);
            List<RawLogLine> lines = new ArrayList<>(n);
            // Cluster timestamps into a few days so date rollovers actually occur.
            long base = 1_700_000_000_000L + (long) rnd.nextInt(30) * day;
            for (int i = 0; i < n; i++) {
                long ts = base + (long) rnd.nextInt(3) * day + rnd.nextInt((int) day);
                String body = rnd.nextInt(4) == 0
                        ? "[12:34:56] <@u:x> 🚀 0xdeadbeef 今天天气不错 lgtm"
                        : "message body " + rnd.nextInt(1000);
                lines.add(new RawLogLine(ts, "@user" + rnd.nextInt(5) + ":matrix.org", body, "$e" + i));
            }
            for (String model : models) {
                for (boolean aiFriendly : new boolean[]{true, false}) {
                    int full = RoomHistoryManager.estimateTokensForReverseRawLines(lines, zone, aiFriendly, model);
                    int incremental = RoomHistoryManager.estimateTokensIncremental(lines, zone, aiFriendly, model);
                    // Accumulating in a different order reorders double additions, so the
                    // sums can differ by ~1 ULP; ceil() then straddles an integer. Allow a
                    // one-token band, which is far below the 15% headroom trim uses.
                    assertTrue(Math.abs(full - incremental) <= 1,
                            "running total must match full recompute within a token"
                                    + " (n=" + n + ", model=" + model
                                    + ", full=" + full + ", incremental=" + incremental + ")");
                }
            }
        }
    }

    /** Running total grows monotonically as lines are gathered. */
    @Test
    void runningTotalGrowsMonotonicallyAsLinesAppend() {
        ZoneId zone = ZoneId.of("UTC");
        List<RawLogLine> lines = new ArrayList<>();
        int prev = 0;
        for (int i = 0; i < 60; i++) {
            lines.add(new RawLogLine(1_700_000_000_000L + i * 1000L, "@u:matrix.org", "body " + i + " 🚀", "$e" + i));
            int now = RoomHistoryManager.estimateTokensIncremental(lines, zone, true, null);
            assertTrue(now > prev, "total must grow with each appended line");
            prev = now;
        }
    }

    @Test
    void conservativeRunningTotalUsesLargestFullFamilyEstimate() {
        ZoneId zone = ZoneId.of("UTC");
        List<RawLogLine> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add(new RawLogLine(1_700_000_000_000L + i * 1000L,
                    "@user123:matrix.org", "1234567890", "$e" + i));
        }

        int gptEstimate = RoomHistoryManager.estimateTokensIncremental(
                lines, zone, true, "openai/gpt-oss-20b");
        int qwenEstimate = RoomHistoryManager.estimateTokensIncremental(
                lines, zone, true, "qwen/qwen3.8-27b");
        int conservativeEstimate = RoomHistoryManager.estimateTokensIncrementalConservative(
                lines, zone, true, List.of("openai/gpt-oss-20b", "qwen/qwen3.8-27b"));

        assertEquals(Math.max(gptEstimate, qwenEstimate), conservativeEstimate);
        assertTrue(conservativeEstimate >= gptEstimate && conservativeEstimate >= qwenEstimate);
    }
}
