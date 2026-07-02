package com.robomwm.ai.matrixrobobot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIRequestQueueTest {

    @Test
    void runsRequestsSequentially() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        Thread first = new Thread(() -> {
            try {
                AIRequestQueue.run("first", () -> {
                    int current = active.incrementAndGet();
                    maxActive.updateAndGet(value -> Math.max(value, current));
                    firstStarted.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                    active.decrementAndGet();
                    return "first";
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread second = new Thread(() -> {
            try {
                firstStarted.await(5, TimeUnit.SECONDS);
                AIRequestQueue.run("second", () -> {
                    int current = active.incrementAndGet();
                    maxActive.updateAndGet(value -> Math.max(value, current));
                    secondStarted.countDown();
                    active.decrementAndGet();
                    return "second";
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        first.start();
        second.start();

        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        assertFalse(secondStarted.await(300, TimeUnit.MILLISECONDS), "Second request should not start until first finishes");

        releaseFirst.countDown();

        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));

        first.join();
        second.join();

        assertEquals(1, maxActive.get());
    }
}
