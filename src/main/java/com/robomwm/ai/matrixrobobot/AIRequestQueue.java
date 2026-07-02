package com.robomwm.ai.matrixrobobot;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Process-wide FIFO gate for outbound AI provider requests.
 */
public final class AIRequestQueue {
    private static final Semaphore PERMIT = new Semaphore(1, true);

    private AIRequestQueue() {
    }

    public static <T> T run(String label, Callable<T> call) throws Exception {
        boolean queued = PERMIT.availablePermits() == 0;
        if (queued) {
            System.out.println("AI request queued: " + label + " (" + PERMIT.getQueueLength() + " already waiting)");
        }

        try {
            PERMIT.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Interrupted while waiting for AI request queue: " + label, e);
        }

        try {
            System.out.println("AI request started: " + label);
            return call.call();
        } finally {
            System.out.println("AI request finished: " + label);
            PERMIT.release();
        }
    }
}
