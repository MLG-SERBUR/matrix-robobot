package com.robomwm.ai.matrixrobobot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
