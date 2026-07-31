package com.masteryj.core;

/**
 * Global synthetic-action budget shared by ALL modules.
 *
 * <p>Hard engineering ceiling:
 * <ul>
 *   <li>Max 1 synthetic action per module per tick window</li>
 *   <li>Max 2 total synthetic actions per tick window (left + right)</li>
 *   <li>Round-robin fairness when both request the same slot</li>
 *   <li>Real vanilla input always has priority, never counted</li>
 *   <li>No tokens, no catch-up, no replay</li>
 * </ul>
 *
 * <p>This is a SINGLETON — one instance shared by AutoLeft and AutoRight.
 */
public final class ActionBudget {

    // Singleton
    public static final ActionBudget INSTANCE = new ActionBudget();

    private int leftCount;
    private int rightCount;
    private int totalCount;
    private long windowStartNanos;
    private boolean leftWonLast;

    private ActionBudget() {}

    public boolean requestLeft() {
        advanceWindow();
        if (leftCount >= 1) return false;
        if (totalCount >= 2) return false;
        if (totalCount == 1 && rightCount > 0) {
            if (leftWonLast) return false;
            leftWonLast = true;
        }
        leftCount++;
        totalCount++;
        return true;
    }

    public boolean requestRight() {
        advanceWindow();
        if (rightCount >= 1) return false;
        if (totalCount >= 2) return false;
        if (totalCount == 1 && leftCount > 0) {
            if (!leftWonLast) return false;
            leftWonLast = false;
        }
        rightCount++;
        totalCount++;
        return true;
    }

    private void advanceWindow() {
        long now = System.nanoTime();
        long elapsed = now - windowStartNanos;
        // Tick window: ~50ms at 20 TPS. Use 40ms threshold for safety.
        if (elapsed > 40_000_000L || windowStartNanos == 0) {
            leftCount = 0;
            rightCount = 0;
            totalCount = 0;
            windowStartNanos = now;
        }
    }

    public void reset() {
        leftCount = 0;
        rightCount = 0;
        totalCount = 0;
        windowStartNanos = 0;
        leftWonLast = false;
    }
}
