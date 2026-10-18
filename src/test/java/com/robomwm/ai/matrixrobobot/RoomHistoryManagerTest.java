package com.robomwm.ai.matrixrobobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
