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

    /** Policy check only — skips the internal fixed-CPS limiter. Caller provides its own timing gate. */
    public boolean shouldEmitFollowUp(boolean enabled,
                                      boolean activeGameplay,
                                      boolean physicalAttackDown,
                                      boolean vanillaEntityTarget) {
        if (!enabled || !activeGameplay || !physicalAttackDown || !vanillaEntityTarget) {
            clearRuntimeState();
            return false;
        }
        return true;
    }

    public void clearRuntimeState() {
        limiter.clearTimingState();
    }

    public void discardOverduePulse() {
        limiter.clearTimingState();
    }
}
