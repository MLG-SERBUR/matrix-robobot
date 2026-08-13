package com.robomwm.ai.matrixrobobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
