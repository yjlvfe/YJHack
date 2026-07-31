package com.masteryj.autoright;

import com.masteryj.core.FixedCpsLimiter;

/**
 * Fixed single-value CPS cadence for held block use.
 *
 * <p>The cadence advances once per client tick, never from elapsed wall time. A missing or stalled
 * tick therefore drops work instead of replaying a backlog. Values from 1-20 produce at most one
 * pulse per tick; 21-40 can produce a second pulse, with an absolute ceiling of two.
 *
 * <p>Candidate loss drops the due pulse. Reacquiring a block face is allowed one immediate pulse so
 * movement at an edge does not feel like the click was eaten. This is an edge response, not catch-up.
 */
public final class LegacyMultiVersionPlacementPolicy {

    static final int TICKS_PER_SECOND = 20;
    static final int MAX_PULSES_PER_TICK = 2;

    private int phase;
    private boolean candidateWasValid;

    /** Fixed-CPS timing gate. */
    public int pulsesThisTick(int configuredCps,
                              boolean enabled,
                              boolean activeGameplay,
                              boolean physicalUseDown,
                              boolean validPlacementCandidate) {
        if (!enabled || !activeGameplay || !physicalUseDown) {
            clearRuntimeState();
            return 0;
        }

        int cps = FixedCpsLimiter.clampCps(configuredCps);
        phase += cps;
        int pulses = Math.min(MAX_PULSES_PER_TICK, phase / TICKS_PER_SECOND);
        phase %= TICKS_PER_SECOND;

        if (!validPlacementCandidate) {
            candidateWasValid = false;
            return 0;
        }

        if (!candidateWasValid && pulses == 0) {
            pulses = 1;
            phase = 0;
        }
        candidateWasValid = true;
        return pulses;
    }

    /**
     * Policy check only — skips internal phase accumulator.
     * Caller provides its own timing gate (e.g. HumanizedCpsLimiter).
     * Returns 1 when conditions are met and placement is valid, 0 otherwise.
     */
    public int pulsesThisTick(boolean enabled,
                              boolean activeGameplay,
                              boolean physicalUseDown,
                              boolean validPlacementCandidate) {
        if (!enabled || !activeGameplay || !physicalUseDown) {
            clearRuntimeState();
            return 0;
        }

        if (!validPlacementCandidate) {
            candidateWasValid = false;
            return 0;
        }

        if (!candidateWasValid) {
            candidateWasValid = true;
            return 1;
        }
        candidateWasValid = true;
        return 1;
    }

    public void clearRuntimeState() {
        phase = 0;
        candidateWasValid = false;
    }

    /** A held block placement may continue only when both the old and new item are blocks. */
    public static boolean canContinueAcrossSlotChange(RightClickPolicy.Kind previous,
                                                       RightClickPolicy.Kind current) {
        return previous == RightClickPolicy.Kind.BLOCK && current == RightClickPolicy.Kind.BLOCK;
    }
}
