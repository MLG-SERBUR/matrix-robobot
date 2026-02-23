package com.robomwm.ai.matrixrobobot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

public class PleadService {
    private final MatrixClient matrixClient;
    private final Path persistenceFile;
    private boolean isEnabled = false;
    private final Random random = new Random();

    public PleadService(MatrixClient matrixClient) {
        this.matrixClient = matrixClient;
        this.persistenceFile = Paths.get("plead_enabled.txt");
        loadState();
    }

    private void loadState() {
        if (Files.exists(persistenceFile)) {
            try {
                String content = Files.readString(persistenceFile).trim();
                isEnabled = "true".equalsIgnoreCase(content);
                System.out.println("Loaded plead enabled state: " + isEnabled);
            } catch (IOException e) {
                System.err.println("Error loading plead state: " + e.getMessage());
            }
        }
    }

    private void saveState() {
        try {
            Files.writeString(persistenceFile, String.valueOf(isEnabled));
        } catch (IOException e) {
            System.err.println("Error saving plead state: " + e.getMessage());
        }
    }

    public void togglePlead(String roomId) {
        isEnabled = !isEnabled;
        saveState();
        if (isEnabled) {
            matrixClient.sendText(roomId, "🥺 feature has been enabled.");
        } else {
            matrixClient.sendText(roomId, "🥺 feature has been disabled.");
        }
    }

    public void processMessage(String roomId, String eventId, String body) {
        if (!isEnabled || body == null) {
            return;
        }

        if (body.contains("🥺")) {
            String reaction;
            if (random.nextBoolean()) {
                reaction = "🥺";
            } else {
                reaction = "👉👈";
            }
            matrixClient.sendReaction(roomId, eventId, reaction);
        }
    }
}
