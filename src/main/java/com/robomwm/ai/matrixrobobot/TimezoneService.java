package com.robomwm.ai.matrixrobobot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TimezoneService {
    private final String storagePath = "user_timezones.json";
    private final ObjectMapper mapper;
    private Map<String, String> userTimezones = new ConcurrentHashMap<>();

    public TimezoneService(ObjectMapper mapper) {
        this.mapper = mapper;
        load();
    }

    private void load() {
        File file = new File(storagePath);
        if (file.exists()) {
            try {
                userTimezones = mapper.readValue(file, new TypeReference<Map<String, String>>() {
                });
            } catch (IOException e) {
                System.err.println("Failed to load user timezones: " + e.getMessage());
            }
        }
    }

    private void save() {
        try {
            mapper.writeValue(new File(storagePath), userTimezones);
        } catch (IOException e) {
            System.err.println("Failed to save user timezones: " + e.getMessage());
        }
    }

    public ZoneId getZoneIdForUser(String userId) {
        String zoneIdStr = userTimezones.get(userId);
        if (zoneIdStr == null) {
            return null;
        }
        try {
            return ZoneId.of(zoneIdStr);
        } catch (Exception e) {
            return null;
        }
    }

    public void setZoneIdForUser(String userId, ZoneId zoneId) {
        userTimezones.put(userId, zoneId.getId());
        save();
    }

    public ZoneId getZoneIdFromAbbr(String timezoneAbbr) {
        if (timezoneAbbr == null)
            return null;
        switch (timezoneAbbr.toUpperCase()) {
            case "PST":
            case "PDT":
                return ZoneId.of("America/Los_Angeles");
            case "MST":
            case "MDT":
                return ZoneId.of("America/Denver");
            case "CST":
            case "CDT":
                return ZoneId.of("America/Chicago");
            case "EST":
            case "EDT":
                return ZoneId.of("America/New_York");
            case "UTC":
                return ZoneId.of("UTC");
            case "GMT":
                return ZoneId.of("GMT");
            default:
                try {
                    return ZoneId.of(timezoneAbbr);
                } catch (Exception e) {
                    return null;
                }
        }
    }

    public ZoneId getZoneId(String userId, String providedAbbr) {
        if (providedAbbr != null) {
            return getZoneIdFromAbbr(providedAbbr);
        }
        return getZoneIdForUser(userId);
    }

    /**
     * Guesses ZoneId based on provided local time string.
     * Supported formats: "HH:mm", "h:mma", "1pm", etc.
     */
    public ZoneId guessZoneIdFromTime(String localTimeStr) {
        String input = localTimeStr.toLowerCase().replace(" ", "").replace(".", "");
        LocalTime time = null;
        String[] patterns = { "H:mm", "HH:mm", "h:mma", "ha", "h:mm a", "h a" };

        for (String pattern : patterns) {
            try {
                DateTimeFormatter dtf = new DateTimeFormatterBuilder()
                        .parseCaseInsensitive()
                        .appendPattern(pattern)
                        .toFormatter(Locale.US);
                time = LocalTime.parse(input, dtf);
                break;
            } catch (Exception ignore) {
            }
        }

        if (time == null) {
            return null;
        }

        LocalDateTime localTime = LocalDateTime.of(java.time.LocalDate.now(), time);

        // Current UTC time
        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);

        // Calculate offset: localTime - nowUtc
        long diffMinutes = java.time.Duration.between(nowUtc.toLocalDateTime(), localTime).toMinutes();

        // Round to nearest 15 minutes as offsets are usually in 15/30/60 min increments
        long offsetMinutes = Math.round(diffMinutes / 15.0) * 15;

        try {
            return ZoneId.ofOffset("UTC", ZoneOffset.ofTotalSeconds((int) offsetMinutes * 60));
        } catch (Exception e) {
            return null;
        }
    }
}
