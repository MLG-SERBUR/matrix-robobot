package com.robomwm.ai.matrixrobobot;

import java.util.HashMap;
import java.util.Map;

public abstract class RepeatedReactionService {
    protected final MatrixClient matrixClient;
    private final Map<String, Boolean> lastMessageWasTrigger = new HashMap<>();
    private final Map<String, Long> lastReactionTime = new HashMap<>();
    private static final long COOLDOWN_MS = 10 * 60 * 1000;

    protected RepeatedReactionService(MatrixClient matrixClient) {
        this.matrixClient = matrixClient;
    }

    protected abstract boolean isTrigger(String body);

    protected abstract String getReaction();

    public void processMessage(String roomId, String eventId, String body, String senderId) {
        processMessage(roomId, eventId, body, senderId, null);
    }

    public void processMessage(String roomId, String eventId, String body, String senderId, String msgtype) {
        if (body == null) {
            return;
        }
        if (msgtype != null && !"m.text".equals(msgtype)) {
            return;
        }
        Long lastReaction = lastReactionTime.get(senderId);
        if (lastReaction != null && System.currentTimeMillis() - lastReaction < COOLDOWN_MS) {
            return;
        }

        boolean isTrigger = isTrigger(body);
        Boolean prevWasTrigger = lastMessageWasTrigger.get(senderId);

        if (isTrigger && prevWasTrigger != null && prevWasTrigger) {
            matrixClient.sendReaction(roomId, eventId, getReaction());
            lastReactionTime.put(senderId, System.currentTimeMillis());
        }

        lastMessageWasTrigger.put(senderId, isTrigger);
    }
}
