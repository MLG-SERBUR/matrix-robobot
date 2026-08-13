package com.robomwm.ai.matrixrobobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Map<String, Object> context = AIService.buildHistoryContext("!room:example.org", "$start", "$end", true, false);

        assertEquals("$start", context.get("startEventId"));
        assertEquals("$end", context.get("endEventId"));
        assertFalse(context.containsKey("hours"));
        assertFalse(context.containsKey("maxMessages"));
    }
}
