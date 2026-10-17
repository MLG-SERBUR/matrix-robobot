package com.robomwm.ai.matrixrobobot;

import java.util.Random;

public class OkReactionService extends RepeatedReactionService {
    private final Random random = new Random();

    public OkReactionService(MatrixClient matrixClient) {
        super(matrixClient);
    }

    @Override
    protected boolean isTrigger(String body) {
        return "ok".equals(body) || "Ok".equals(body);
    }

    @Override
    protected String getReaction() {
        return PleadService.THIRD_CHANCE_REACTIONS[random.nextInt(PleadService.THIRD_CHANCE_REACTIONS.length)];
    }
}
