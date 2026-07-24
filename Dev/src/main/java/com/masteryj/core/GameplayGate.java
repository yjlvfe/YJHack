package com.masteryj.core;

/**
 * Pure predicate for "may synthetic click automation run right now?". Extracted from the
 * identical {@code isInActiveGameplay} guards in AutoLeft and AutoRight so the exact gating
 * contract can be unit-tested without a Minecraft runtime.
 *
 * <p>Automation is allowed only in real, focused, first-person gameplay: a live (non-spectator)
 * player in a loaded world, no screen open, window focused, mouse captured. Any false → no
 * synthetic action (and the caller resets its pending schedule).
 */
public final class GameplayGate {

    private GameplayGate() {
    }

    public static boolean active(boolean hasPlayer, boolean hasWorld, boolean screenOpen,
                                 boolean windowFocused, boolean cursorLocked,
                                 boolean alive, boolean spectator) {
        return hasPlayer
                && hasWorld
                && !screenOpen
                && windowFocused
                && cursorLocked
                && alive
                && !spectator;
    }
}
