package com.masteryj.core;

/**
 * Global synthetic-action budget shared by AutoLeft and AutoRight.
 *
 * <p>Hard engineering ceiling, NOT camouflage:
 * <ul>
 *   <li>Each module: at most 1 synthetic action per tick</li>
 *   <li>Combined: at most 2 total per tick (1 left + 1 right)</li>
 *   <li>If both request in the same tick and budget is 1, round-robin
 *       alternates which module gets the slot</li>
 *   <li>Real vanilla input always has priority and is never counted</li>
 *   <li>No tokens, no catch-up, no replay</li>
 * </ul>
 */
public final class ActionBudget {

    private static final int MAX_PER_MODULE = 1;
    private static final int MAX_COMBINED = 2;
    private static final int MAX_STRICT = 1;

    private int tickLeftCount;
    private int tickRightCount;
    private int tickTotalCount;
    private long lastTickNanos;

    // Round-robin: when budget is 1 and both want it, alternate
    private boolean leftWonLast;

    /**
     * Ask permission for AutoLeft to fire one synthetic action this tick.
     *
     * @param nowNanos  monotonic nanoTime
     * @param strict   if true, only 1 total action allowed this tick (round-robin)
     * @return true if allowed
     */
    public boolean requestLeft(long nowNanos, boolean strict) {
        resetIfNewTick(nowNanos);

        if (tickLeftCount >= MAX_PER_MODULE) return false;

        int limit = strict ? MAX_STRICT : MAX_COMBINED;

        if (tickTotalCount >= limit) return false;
        if (limit == MAX_STRICT && tickRightCount > 0) {
            // Only 1 slot this tick — round-robin fairness
            if (leftWonLast) return false;
            leftWonLast = true;
        }

        tickLeftCount++;
        tickTotalCount++;
        return true;
    }

    /**
     * Ask permission for AutoRight to fire one synthetic action this tick.
     */
    public boolean requestRight(long nowNanos, boolean strict) {
        resetIfNewTick(nowNanos);

        if (tickRightCount >= MAX_PER_MODULE) return false;

        int limit = strict ? MAX_STRICT : MAX_COMBINED;

        if (tickTotalCount >= limit) return false;
        if (limit == MAX_STRICT && tickLeftCount > 0) {
            if (!leftWonLast) return false;
            leftWonLast = false;
        }

        tickRightCount++;
        tickTotalCount++;
        return true;
    }

    private void resetIfNewTick(long nowNanos) {
        long diff = nowNanos - lastTickNanos;
        // A tick is ~50ms (20 TPS). Use 40ms as threshold.
        if (diff > 40_000_000L || lastTickNanos == 0) {
            tickLeftCount = 0;
            tickRightCount = 0;
            tickTotalCount = 0;
            lastTickNanos = nowNanos;
        }
    }

    /** Call when world changes, GUI opens, focus lost, or module disabled. */
    public void reset() {
        tickLeftCount = 0;
        tickRightCount = 0;
        tickTotalCount = 0;
        lastTickNanos = 0;
        leftWonLast = false;
    }
}
