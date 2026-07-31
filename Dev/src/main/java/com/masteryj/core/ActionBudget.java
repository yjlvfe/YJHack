package com.masteryj.core;

/**
 * Global synthetic-action budget shared by ALL modules.
 *
 * <p>Hard engineering ceiling:
 * <ul>
 *   <li>Max 1 synthetic action per module per tick window</li>
 *   <li>Max 2 total synthetic actions per tick window (left + right)</li>
 *   <li>Real vanilla input always has priority, never counted</li>
 *   <li>No tokens, no catch-up, no replay</li>
 * </ul>
 *
 * <p>This is a SINGLETON — one instance shared by AutoLeft and AutoRight.
 */
public final class ActionBudget {

    public static final ActionBudget INSTANCE = new ActionBudget();

    private int leftCount;
    private int rightCount;
    private int totalCount;
    private long windowStartNanos;

    private ActionBudget() {}

    public boolean requestLeft() {
        if (!advanceWindow()) return false;
        if (leftCount >= 1 || totalCount >= 2) return false;
        leftCount++;
        totalCount++;
        return true;
    }

    public boolean requestRight() {
        if (!advanceWindow()) return false;
        if (rightCount >= 1 || totalCount >= 2) return false;
        rightCount++;
        totalCount++;
        return true;
    }

    /**
     * Returns true if the budget window is still active.
     * When the window expires, counters reset and a new window begins.
     */
    private boolean advanceWindow() {
        long now = System.nanoTime();
        long elapsed = now - windowStartNanos;
        if (elapsed > 40_000_000L || windowStartNanos == 0) {
            leftCount = 0;
            rightCount = 0;
            totalCount = 0;
            windowStartNanos = now;
        }
        return true;
    }

    public void reset() {
        leftCount = 0;
        rightCount = 0;
        totalCount = 0;
        windowStartNanos = 0;
    }
}
