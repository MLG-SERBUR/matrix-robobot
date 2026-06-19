package com.robomwm.ai.matrixrobobot;

import java.util.HashMap;
import java.util.Map;

public class OkReactionService extends PleadService {
    private final Map<String, Boolean> lastMessageWasOk = new HashMap<>();

    public OkReactionService(MatrixClient matrixClient) {
        super(matrixClient);
    }

    @Override
    public void processMessage(String roomId, String eventId, String body, String senderId) {
        if (body == null) {
            return;
        }

        boolean isOk = "ok".equals(body);
        Boolean prevWasOk = lastMessageWasOk.get(senderId);

        if (isOk && prevWasOk != null && prevWasOk) {
            String reaction = THIRD_CHANCE_REACTIONS[random.nextInt(THIRD_CHANCE_REACTIONS.length)];
            matrixClient.sendReaction(roomId, eventId, reaction);
        }

        lastMessageWasOk.put(senderId, isOk);
    }
}
