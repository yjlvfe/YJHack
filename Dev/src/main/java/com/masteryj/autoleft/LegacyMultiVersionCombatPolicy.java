package com.masteryj.autoleft;

import com.masteryj.core.FixedCpsLimiter;

/**
 * Attempt-timing policy for a modern client on a legacy-combat, protocol-translated server.
 *
 * <p>The policy never queues, replays, catches up, or emits more than one follow-up attempt per
 * callback. It only allows a follow-up when the physical hold is active and Minecraft's current
 * vanilla crosshair result is an eligible entity target. Server acceptance and damage are outside
 * the client's control.
 */
public final class LegacyMultiVersionCombatPolicy {

    private final FixedCpsLimiter limiter = new FixedCpsLimiter();

    public boolean shouldEmitFollowUp(long nowNanos,
                                      int cps,
                                      boolean enabled,
                                      boolean activeGameplay,
                                      boolean physicalAttackDown,
                                      boolean vanillaEntityTarget) {
        if (!enabled || !activeGameplay || !physicalAttackDown || !vanillaEntityTarget) {
            clearRuntimeState();
            return false;
        }
        return limiter.acquire(nowNanos, cps);
    }

    /** Clears timing only. It does not change settings or create input. */
    public void clearRuntimeState() {
        limiter.reset();
    }

    /** Explicit name for dropping overdue work; there is no queue to drain. */
    public void discardOverduePulse() {
        limiter.reset();
    }
}
