package com.masteryj.autoright;

import com.masteryj.core.FixedCpsLimiter;

/**
 * Fixed-rate block-use attempt policy for protocol-translated legacy servers.
 *
 * <p>Vanilla remains responsible for interaction packets, sequence ids, prediction, collision,
 * and the final placement decision. This policy only decides whether a single follow-up call may
 * reach vanilla; it owns no packet queue and performs no catch-up after stalls.
 */
public final class LegacyMultiVersionPlacementPolicy {

    private final FixedCpsLimiter limiter = new FixedCpsLimiter();

    public boolean shouldEmitFollowUp(long nowNanos,
                                      int cps,
                                      boolean enabled,
                                      boolean activeGameplay,
                                      boolean physicalUseDown,
                                      boolean validPlacementCandidate) {
        if (!enabled || !activeGameplay || !physicalUseDown || !validPlacementCandidate) {
            clearRuntimeState();
            return false;
        }
        return limiter.acquire(nowNanos, cps);
    }

    public void clearRuntimeState() {
        limiter.reset();
    }

    public void discardOverduePulse() {
        limiter.reset();
    }

    /** A held block placement may continue only when both the old and new item are blocks. */
    public static boolean canContinueAcrossSlotChange(RightClickPolicy.Kind previous,
                                                       RightClickPolicy.Kind current) {
        return previous == RightClickPolicy.Kind.BLOCK && current == RightClickPolicy.Kind.BLOCK;
    }
}
