package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks the gating contract shared by AutoLeft/AutoRight: synthetic click automation runs
 * only in real, focused, first-person gameplay. Any adverse condition blocks all synthetic
 * actions (GUI open, focus loss, world null, etc.).
 */
class GameplayGateTest {

    // Fully-active baseline; each test flips exactly one input.
    private static final boolean PLAYER = true;
    private static final boolean WORLD = true;
    private static final boolean SCREEN_OPEN = false;
    private static final boolean FOCUSED = true;
    private static final boolean CURSOR = true;
    private static final boolean ALIVE = true;
    private static final boolean SPECTATOR = false;

    @Test
    void allConditionsGoodIsActive() {
        assertTrue(GameplayGate.active(PLAYER, WORLD, SCREEN_OPEN, FOCUSED, CURSOR, ALIVE, SPECTATOR));
    }

    @Test
    void guiOpenBlocksSyntheticActions() {
        assertFalse(GameplayGate.active(PLAYER, WORLD, true, FOCUSED, CURSOR, ALIVE, SPECTATOR));
    }

    @Test
    void focusLossBlocksSyntheticActions() {
        assertFalse(GameplayGate.active(PLAYER, WORLD, SCREEN_OPEN, false, CURSOR, ALIVE, SPECTATOR));
    }

    @Test
    void worldNullBlocksSyntheticActions() {
        assertFalse(GameplayGate.active(PLAYER, false, SCREEN_OPEN, FOCUSED, CURSOR, ALIVE, SPECTATOR));
    }

    @Test
    void playerNullBlocksSyntheticActions() {
        assertFalse(GameplayGate.active(false, WORLD, SCREEN_OPEN, FOCUSED, CURSOR, ALIVE, SPECTATOR));
    }

    @Test
    void unlockedCursorBlocksSyntheticActions() {
        assertFalse(GameplayGate.active(PLAYER, WORLD, SCREEN_OPEN, FOCUSED, false, ALIVE, SPECTATOR));
    }

    @Test
    void deadOrSpectatorBlocksSyntheticActions() {
        assertFalse(GameplayGate.active(PLAYER, WORLD, SCREEN_OPEN, FOCUSED, CURSOR, false, SPECTATOR));
        assertFalse(GameplayGate.active(PLAYER, WORLD, SCREEN_OPEN, FOCUSED, CURSOR, ALIVE, true));
    }
}
