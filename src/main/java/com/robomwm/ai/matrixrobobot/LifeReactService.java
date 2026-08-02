package com.robomwm.ai.matrixrobobot;

import java.util.Random;

public class LifeReactService {
    private final MatrixClient matrixClient;
    private final Random random = new Random();

    private static final String[] OTHER_FIST_REACTIONS = {
        "✊👊", "👊✊", "✊✊", "👊👊",
        "🤛🤛", "🤜🤜", "✊👊", "👊✊", "✊✊"
    };

    public LifeReactService(MatrixClient matrixClient) {
        this.matrixClient = matrixClient;
    }

    public void processMessage(String roomId, String eventId, String body, String senderId) {
        if (body == null || !body.contains("🥺")) {
            return;
        }

        String reaction;
        int chance = random.nextInt(6);
        if (chance == 0) {
            reaction = "🥺";
        } else if (chance == 1) {
            reaction = "🤜🤛";
        } else if (chance == 2) {
            reaction = "🤜😵🤛";
        } else if (chance == 3) {
            reaction = "🤛🤜";
        } else if (chance == 4) {
            reaction = OTHER_FIST_REACTIONS[random.nextInt(OTHER_FIST_REACTIONS.length)];
        } else {
            reaction = PleadService.THIRD_CHANCE_REACTIONS[random.nextInt(PleadService.THIRD_CHANCE_REACTIONS.length)];
        }
        matrixClient.sendReaction(roomId, eventId, reaction);
    }
}
