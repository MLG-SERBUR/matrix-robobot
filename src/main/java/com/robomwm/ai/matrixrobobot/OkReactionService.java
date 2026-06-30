package com.robomwm.ai.matrixrobobot;

import java.util.HashMap;
import java.util.Map;

public class OkReactionService extends PleadService {
    private final Map<String, Boolean> lastMessageWasOk = new HashMap<>();
    private final Map<String, Long> lastReactionTime = new HashMap<>();
    private static final long COOLDOWN_MS = 10 * 60 * 1000; // 10 minutes

    public OkReactionService(MatrixClient matrixClient) {
        super(matrixClient);
    }

    @Override
    public void processMessage(String roomId, String eventId, String body, String senderId) {
        processMessage(roomId, eventId, body, senderId, null);
    }

    public void processMessage(String roomId, String eventId, String body, String senderId, String msgtype) {
        if (body == null) {
            return;
        }

        // Only react to plain text messages (no msgtype or msgtype is m.text)
        if (msgtype != null && !"m.text".equals(msgtype)) {
            return;
        }

        // Check cooldown - don't react if already reacted to this person in the past 10 minutes
        Long lastReaction = lastReactionTime.get(senderId);
        if (lastReaction != null && System.currentTimeMillis() - lastReaction < COOLDOWN_MS) {
            return;
        }

        boolean isOk = "ok".equals(body) || "Ok".equals(body);
        Boolean prevWasOk = lastMessageWasOk.get(senderId);

        if (isOk && prevWasOk != null && prevWasOk) {
            String reaction = THIRD_CHANCE_REACTIONS[random.nextInt(THIRD_CHANCE_REACTIONS.length)];
            matrixClient.sendReaction(roomId, eventId, reaction);
            lastReactionTime.put(senderId, System.currentTimeMillis());
        }

        lastMessageWasOk.put(senderId, isOk);
    }
}
