package com.robomwm.ai.matrixrobobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CommandDispatcherTest {

    @Test
    void askCommandAcceptsMultilinePrompt() {
        assertTrue(CommandDispatcher.isAskCommand("!ask First line\nSecond line"));
    }

    @Test
    void askCommandRejectsOtherCommands() {
        assertFalse(CommandDispatcher.isAskCommand("!asking First line"));
    }

    @Test
    void historyContextUsesEventWindowOnly() {
        Map<String, Object> context = AIService.buildHistoryContext("!room:example.org", "$start", "$end", false);

        assertEquals("$start", context.get("startEventId"));
        assertEquals("$end", context.get("endEventId"));
        assertFalse(context.containsKey("hours"));
        assertFalse(context.containsKey("maxMessages"));
    }

    @Test
    void textSearchCommandParsesUserFilterAndAlias() {
        CommandDispatcher.ParsedTextSearchCommand parsed = CommandDispatcher.parseTextSearchCommand("!textsearch 24h user:alice hello world", "!textsearch");

        assertEquals(24, parsed.hours);
        assertEquals("hello world", parsed.query);
        assertEquals(1, parsed.filterSenders.size());
        assertEquals("alice", parsed.filterSenders.get(0));
    }

    @Test
    void textSearchOnlyMatchesMessageBodyNotSenderMetadata() {
        assertTrue(TextSearchService.matchesSearch("hello world", new String[] {"world"}));
        assertFalse(TextSearchService.matchesSearch("hello world", new String[] {"up"}));
        assertFalse(TextSearchService.matchesSearch("matrix.org", new String[] {"up"}));
    }

    @Test
    void textSearchRendersDateAsLinkLikeMatrixSearch() throws Exception {
        Class<?> stateClass = Class.forName("com.robomwm.ai.matrixrobobot.TextSearchService$TextSearchPaginationState");
        Constructor<?> ctor = stateClass.getDeclaredConstructor(
                String.class, String.class, String[].class, String.class, String.class, String.class,
                ZoneId.class, long.class, long.class, List.class);
        ctor.setAccessible(true);
        Object state = ctor.newInstance(
                "alice", "up", new String[] {"up"}, "!room:example.org", "!room:example.org",
                "$evt", ZoneId.of("UTC"), 0L, 60_000L, List.of());

        Class<?> hitClass = Class.forName("com.robomwm.ai.matrixrobobot.TextSearchService$TextSearchHit");
        Constructor<?> hitCtor = hitClass.getDeclaredConstructor(String.class, String.class);
        hitCtor.setAccessible(true);
        Object hit = hitCtor.newInstance("[2026-08-15 07:27] <@alice:example.org> up", "$evt");

        Field allResultsField = stateClass.getDeclaredField("allResults");
        allResultsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<Object> allResults = (java.util.List<Object>) allResultsField.get(state);
        allResults.add(hit);

        Object result = stateClass.getDeclaredMethod("renderPage").invoke(state);
        String rendered = (String) result;

        assertTrue(rendered.contains("**[2026-08-15 07:27](https://matrix.to/#/!room:example.org/$evt)**"));
    }
}
