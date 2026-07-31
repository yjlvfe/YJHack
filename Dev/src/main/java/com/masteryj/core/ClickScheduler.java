package com.masteryj.core;

/**
 * Tick-aware phase accumulator for held synthetic input.
 *
 * <p>The physical rising-edge press is always owned by vanilla. This scheduler starts from zero
 * after a fresh press and emits only follow-up input. Minecraft runs at twenty logical ticks per
 * second, so the safe executable ceiling is twenty CPS and no tick may contain more than one
 * synthetic event. Missed ticks are never replayed and elapsed wall time is intentionally ignored.
 */
public final class ClickScheduler {

    public static final int TICKS_PER_SECOND = 20;
    public static final int MAX_CPS = 20;
    public static final int MAX_PULSES_PER_TICK = 1;

    private int phase;

    /** Drop cadence progress. The next physical press remains vanilla-only. */
    public void clear() {
        phase = 0;
    }

    /** Advance exactly one client tick and return either zero or one follow-up input event. */
    public int pulsesThisTick(int cps) {
        int safeCps = Math.max(0, Math.min(MAX_CPS, cps));
        phase += safeCps;
        if (phase < TICKS_PER_SECOND) return 0;
        phase -= TICKS_PER_SECOND;
        if (phase >= TICKS_PER_SECOND) phase = TICKS_PER_SECOND - 1;
        return 1;
    }
}
