package com.masteryj.core;

/**
 * Monotonic fixed-rate limiter for direct client actions.
 *
 * <p>At most one action is released per callback. Missed time is discarded by scheduling from
 * {@code now}, so lag, low FPS, menus and focus changes can never create catch-up bursts.
 */
public final class FixedCpsLimiter {

    public static final int MAX_CPS = 20;
    private static final long SECOND_NANOS = 1_000_000_000L;

    private long nextActionAtNanos = Long.MIN_VALUE;

    /**
     * Returns true when exactly one action is due. This method never returns more than one action
     * and never carries missed actions forward.
     */
    public boolean acquire(long nowNanos, int configuredCps) {
        int cps = clampCps(configuredCps);
        long interval = Math.max(1L, SECOND_NANOS / cps);
        if (nextActionAtNanos == Long.MIN_VALUE) {
            nextActionAtNanos = nowNanos + interval;
            return false;
        }
        if (nowNanos < nextActionAtNanos) return false;

        // Schedule from the real current time, not from the old deadline: no backlog/catch-up.
        nextActionAtNanos = nowNanos + interval;
        return true;
    }

    /** Clears timing only; no action is emitted and no setting is changed. */
    public void clearTimingState() {
        nextActionAtNanos = Long.MIN_VALUE;
    }

    public static int clampCps(int cps) {
        return Math.max(1, Math.min(MAX_CPS, cps));
    }
}
