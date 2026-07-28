package com.robomwm.ai.matrixrobobot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LifeReactService {
    private final MatrixClient matrixClient;
    private final Random random = new Random();
    private static final Pattern WORD_PATTERN = Pattern.compile("\\b\\w+\\b");

    public LifeReactService(MatrixClient matrixClient) {
        this.matrixClient = matrixClient;
    }

    public void processMessage(String roomId, String eventId, String body, String senderId) {
        if (body == null || !body.contains("🥺")) {
            return;
        }

        String reaction;
        if (random.nextInt(2) == 0) {
            reaction = PleadService.THIRD_CHANCE_REACTIONS[random.nextInt(PleadService.THIRD_CHANCE_REACTIONS.length)];
        } else {
            reaction = getRandomWord(body);
        }
        matrixClient.sendReaction(roomId, eventId, reaction);
    }

    private String getRandomWord(String body) {
        Matcher matcher = WORD_PATTERN.matcher(body);
        List<String> words = new ArrayList<>();
        while (matcher.find()) {
            words.add(matcher.group());
        }
        if (words.isEmpty()) {
            return PleadService.THIRD_CHANCE_REACTIONS[random.nextInt(PleadService.THIRD_CHANCE_REACTIONS.length)];
        }
        return words.get(random.nextInt(words.size()));
    }
}
