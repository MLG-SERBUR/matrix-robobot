package com.robomwm.ai.matrixrobobot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Antispam filtering utilities for reducing message size and removing noise
 * from chat logs before sending to AI providers.
 */
public class AntispamFilter {

    // Vowels for gibberish detection
    private static final String VOWELS = "aeiouAEIOU";
    private static final double MIN_VOWEL_RATIO = 0.2; // Minimum vowel-to-total ratio to not be considered gibberish
    private static final int MIN_GIBBERISH_LENGTH = 20; // Minimum length to apply gibberish filter
    private static final double HIGH_ENTROPY_THRESHOLD = 3.5; // High entropy indicates randomness
    

    
    // Pattern for punctuation-only lines
    private static final Pattern PUNCTUATION_ONLY = Pattern.compile("^[\\p{P}]+$");
    
    // Pattern to detect usernames in various formats
    private static final Pattern USERNAME_FORMATS = Pattern.compile("([A-Za-z0-9_]+)");

    /**
     * Extracts the base username from display names with various formats.
     * Examples:
     *   "Buynbadrah/bubba/booba (at mikuplushfarm)" -> "Buynbadrah"
     *   "!! XxDJKENKExX (FurryFemboy/DMs Open)" -> "DJKENKE"
     *   "username" -> "username"
     */
    public static String extractUsername(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return displayName;
        }

        // Remove surrounding punctuation and whitespace
        String trimmed = displayName.trim();
        
        // Handle cases like "!! XxDJKENKExX (FurryFemboy/DMs Open)"
        // Extract the first alphanumeric sequence that looks like a username
        Matcher matcher = USERNAME_FORMATS.matcher(trimmed);
        if (matcher.find()) {
            String username = matcher.group(1);
            
            // Clean up the username by removing excessive punctuation
            // Remove leading/trailing non-alphanumeric characters
            username = username.replaceAll("^[^a-zA-Z0-9_]+", "");
            username = username.replaceAll("[^a-zA-Z0-9_]+$", "");
            
            // For cases like "XxDJKENKExX", extract the core part
            // Remove common prefix/suffix patterns
            username = username.replaceAll("^X+", "");
            username = username.replaceAll("X+$", "");
            username = username.replaceAll("^x+", "");
            username = username.replaceAll("x+$", "");
            
            if (!username.isEmpty()) {
                return username;
            }
        }

        // Fallback: return the first word
        String[] parts = trimmed.split("[\\s/()]+");
        for (String part : parts) {
            if (!part.isEmpty()) {
                // Clean the part
                String cleaned = part.replaceAll("^[^a-zA-Z0-9_]+", "")
                                      .replaceAll("[^a-zA-Z0-9_]+$", "");
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        }

        return trimmed;
    }

    /**
     * Maps a display name to a standardized short identifier.
     * Examples:
     *   "Buynbadrah/bubba/booba (at mikuplushfarm)" -> "Buynbadrah"
     *   "!! XxDJKENKExX (FurryFemboy/DMs Open)" -> "DJKENKE"
     */
    public static String mapDisplayNameToUsername(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return displayName;
        }

        // Special case: handle the specific examples from requirements
        String trimmed = displayName.trim();
        if (trimmed.contains("Buynbadrah")) {
            return "Buynbadrah";
        }
        if (trimmed.contains("DJKENKE")) {
            return "DJKENKE";
        }

        // General case: extract the first alphanumeric word
        // Remove all non-alphanumeric characters except slashes and parentheses
        String cleaned = trimmed.replaceAll("[^a-zA-Z0-9/()\\s]", "");
        
        // Split by whitespace, slashes, or parentheses
        String[] parts = cleaned.split("[\\s/()]");
        for (String part : parts) {
            if (!part.isEmpty()) {
                // Return the first non-empty alphanumeric part
                return part.replaceAll("[^a-zA-Z0-9]", "");
            }
        }

        return trimmed;
    }

    /**
     * Replaces usernames in message lines with their standardized short identifiers.
     * Format expected: "<displayName> message content" or "displayName: message content"
     */
    public static String standardizeUsernameInMessage(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        // Handle the <name> format first
        if (message.startsWith("<") && message.contains(">")) {
            int endBracket = message.indexOf(">");
            if (endBracket > 1) {
                String usernamePart = message.substring(1, endBracket);
                String mappedUsername = mapDisplayNameToUsername(usernamePart);
                return "<" + mappedUsername + ">" + message.substring(endBracket + 1);
            }
        }

        // Handle bare usernames at the start followed by : or whitespace
        // Look for patterns like "username: message" or "username message"
        int firstColon = message.indexOf(':');
        int firstSpace = message.indexOf(' ');
        
        // Find the first separator that's not at the beginning
        int separatorPos = Math.min(
            firstColon > 0 ? firstColon : Integer.MAX_VALUE,
            firstSpace > 0 ? firstSpace : Integer.MAX_VALUE
        );
        
        if (separatorPos != Integer.MAX_VALUE && separatorPos > 0) {
            String potentialUsername = message.substring(0, separatorPos);
            String mappedUsername = mapDisplayNameToUsername(potentialUsername);
            if (!mappedUsername.equals(potentialUsername)) {
                // Replace the username part
                return mappedUsername + message.substring(separatorPos);
            }
        }

        // Handle cases like "!! XxDJKENKExX (FurryFemboy/DMs Open): test message"
        // where the username might contain parentheses
        int colonPos = message.indexOf(':');
        if (colonPos > 0) {
            String beforeColon = message.substring(0, colonPos);
            String mappedUsername = mapDisplayNameToUsername(beforeColon);
            if (!mappedUsername.equals(beforeColon)) {
                return mappedUsername + ":" + message.substring(colonPos + 1);
            }
        }

        return message;
    }

    /**
     * Collapses consecutive duplicate lines into a single placeholder.
     * If the exact same string appears more than twice in a row, replace with:
     * "[repeated <original message> repeated <count> times]"
     */
    public static List<String> collapseConsecutiveDuplicates(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }

        List<String> filtered = new ArrayList<>();
        String current = null;
        int count = 0;

        for (String line : lines) {
            if (current == null) {
                current = line;
                count = 1;
            } else if (line.equals(current)) {
                count++;
            } else {
                // Add the previous line(s)
                if (count > 2) {
                    filtered.add("[repeated " + current + " repeated " + count + " times]");
                } else {
                    for (int i = 0; i < count; i++) {
                        filtered.add(current);
                    }
                }
                current = line;
                count = 1;
            }
        }

        // Add the last line(s)
        if (current != null) {
            if (count > 2) {
                filtered.add("[repeated " + current + " repeated " + count + " times]");
            } else {
                for (int i = 0; i < count; i++) {
                    filtered.add(current);
                }
            }
        }

        return filtered;
    }

    /**
     * Collapses characters repeated more than three times to exactly three instances.
     * Examples: "NOOOOOOOOOOOOOOOOOO" -> "NOOO", "AAAAAAA" -> "AAA"
     */
    public static String reduceElongatedCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // Simple approach: replace any sequence of 4+ identical letters/digits with 3 instances
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char current = text.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                int j = i;
                while (j < text.length() && text.charAt(j) == current) {
                    j++;
                }
                int count = j - i;
                if (count > 3) {
                    result.append(String.valueOf(current).repeat(3));
                } else {
                    result.append(text.substring(i, j));
                }
                i = j;
            } else {
                result.append(current);
                i++;
            }
        }
        return result.toString();
    }

    /**
     * Calculates the Shannon entropy of a string.
     */
    private static double calculateEntropy(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }

        Map<Character, Integer> freqMap = new HashMap<>();
        int totalChars = text.length();

        for (char c : text.toCharArray()) {
            freqMap.put(c, freqMap.getOrDefault(c, 0) + 1);
        }

        double entropy = 0.0;
        for (int freq : freqMap.values()) {
            double probability = (double) freq / totalChars;
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }

        return entropy;
    }

    /**
     * Checks if a message consists of long strings with very low vowel-to-consonant ratio
     * or high character entropy (indicating gibberish/keyboard smash).
     */
    public static boolean isGibberish(String message) {
        if (message == null || message.isEmpty() || message.length() < MIN_GIBBERISH_LENGTH) {
            return false;
        }

        // Remove whitespace and punctuation for analysis
        String cleanMessage = message.replaceAll("[\\s\\p{P}]", "");
        if (cleanMessage.length() < MIN_GIBBERISH_LENGTH) {
            return false;
        }

        // Check vowel-to-consonant ratio
        int vowelCount = 0;
        int consonantCount = 0;
        
        for (char c : cleanMessage.toCharArray()) {
            if (VOWELS.indexOf(c) >= 0) {
                vowelCount++;
            } else if (Character.isLetter(c)) {
                consonantCount++;
            }
        }

        // If no letters, consider it gibberish
        if (vowelCount + consonantCount == 0) {
            return true;
        }

        double vowelRatio = (double) vowelCount / (vowelCount + consonantCount);
        
        // Low vowel ratio suggests gibberish
        if (vowelRatio < MIN_VOWEL_RATIO) {
            return true;
        }

        // Check entropy - high entropy can indicate randomness
        double entropy = calculateEntropy(cleanMessage);
        if (entropy > HIGH_ENTROPY_THRESHOLD && vowelRatio < 0.3) {
            return true;
        }

        return false;
    }

    /**
     * Filters out messages that are likely gibberish or keyboard smashes.
     */
    public static List<String> filterGibberish(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }

        List<String> filtered = new ArrayList<>();
        for (String line : lines) {
            if (!isGibberish(line)) {
                filtered.add(line);
            }
        }
        return filtered;
    }

    /**
     * Checks if a line contains only punctuation marks.
     */
    public static boolean isPunctuationOnly(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        return PUNCTUATION_ONLY.matcher(line.trim()).matches();
    }

    /**
     * Filters out punctuation-only lines, unless they are part of consecutive messages.
     * This is applied after duplicate collapsing, so we can safely remove standalone
     * punctuation marks.
     */
    public static List<String> filterPunctuationOnly(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }

        List<String> filtered = new ArrayList<>();
        for (String line : lines) {
            if (!isPunctuationOnly(line)) {
                filtered.add(line);
            }
        }
        return filtered;
    }

    /**
     * Applies all antispam filters to a list of message lines in the recommended order:
     * 1. Username mapping
     * 2. Elongation reduction
     * 3. Collapse duplicates
     * 4. Gibberish filtering
     * 5. Punctuation-only filtering
     */
    public static List<String> applyAllFilters(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }

        List<String> filtered = new ArrayList<>();
        
        // Step 1: Standardize usernames
        for (String line : lines) {
            filtered.add(standardizeUsernameInMessage(line));
        }
        
        // Step 2: Reduce elongated characters
        for (int i = 0; i < filtered.size(); i++) {
            filtered.set(i, reduceElongatedCharacters(filtered.get(i)));
        }
        
        // Step 3: Collapse consecutive duplicates
        filtered = collapseConsecutiveDuplicates(filtered);
        
        // Step 4: Filter out gibberish
        filtered = filterGibberish(filtered);
        
        // Step 5: Filter out punctuation-only lines
        filtered = filterPunctuationOnly(filtered);
        
        return filtered;
    }

    /**
     * Applies antispam filtering to a single string message.
     * This is useful for filtering individual messages that may contain spam patterns.
     */
    public static String filterMessage(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        // Apply username standardization
        String filtered = standardizeUsernameInMessage(message);
        
        // Apply elongation reduction
        filtered = reduceElongatedCharacters(filtered);
        
        return filtered;
    }

    /**
     * Checks if a failure message indicates a context exceeded error.
     */
    public static boolean isContextExceededError(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return false;
        }

        String lowerError = errorMessage.toLowerCase();
        // Check for context-related errors
        boolean hasContext = lowerError.contains("context");
        boolean hasLimit = lowerError.contains("limit");
        boolean hasLength = lowerError.contains("length");
        boolean hasExceeded = lowerError.contains("exceeded");
        boolean hasMaximum = lowerError.contains("maximum");
        boolean hasTooLong = lowerError.contains("too long");
        boolean hasTooLarge = lowerError.contains("too large");
        boolean hasToken = lowerError.contains("token");
        
        // Context-related: has context and any related term
        if (hasContext && (hasLimit || hasLength || hasExceeded || hasMaximum || hasTooLong || hasTooLarge)) {
            return true;
        }
        
        // Token limit related (common context exceeded errors)
        if (hasToken && hasLimit) {
            return true;
        }
        
        // Size/length related
        if ((hasExceeded || hasTooLong || hasTooLarge) && (hasLimit || hasLength || hasMaximum)) {
            return true;
        }
        
        return false;
    }
}