package com.robomwm.ai.matrixrobobot;

public class ShushReactionService extends RepeatedReactionService {
    public ShushReactionService(MatrixClient matrixClient) {
        super(matrixClient);
    }

    @Override
    protected boolean isTrigger(String body) {
        return "Shush".equals(body) || "shush".equals(body);
    }

    @Override
    protected String getReaction() {
        return "Shush buynbadruh";
    }
}
