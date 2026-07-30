package com.masteryj.core;

/**
 * Tick-aware phase accumulator for held synthetic actions.
 *
 * <p>The physical press itself is always left to vanilla. This scheduler therefore starts from
 * zero after every fresh press and only produces follow-up held actions. That prevents the old
 * behaviour where the real click and an "immediate" synthetic click landed in the same tick.
 *
 * <p>At twenty client ticks per second, 1..20 CPS produces at most one pulse in a tick and
 * 21..40 CPS can produce two. Missed ticks are never replayed: elapsed wall time is not used and
 * the phase is always kept below one pulse after every call.
 */
public final class ClickScheduler {

    public static final int TICKS_PER_SECOND = 20;
    public static final int MAX_CPS = 40;
    public static final int MAX_PULSES_PER_TICK = 2;

    private int phase;

    /** Drop all cadence progress. The next physical press is handled by vanilla, not here. */
    public void clear() {
        phase = 0;
    }

    /**
     * Advance exactly one client tick and return 0..2 follow-up synthetic pulses.
     */
    public int pulsesThisTick(int cps) {
        int safeCps = Math.max(0, Math.min(MAX_CPS, cps));
        phase += safeCps;

        int pulses = 0;
        while (phase >= TICKS_PER_SECOND && pulses < MAX_PULSES_PER_TICK) {
            phase -= TICKS_PER_SECOND;
            pulses++;
        }

        // Defensive no-catch-up guard. With safeCps <= 40 this is normally already true.
        if (phase >= TICKS_PER_SECOND) phase = TICKS_PER_SECOND - 1;
        return pulses;
    }
}
