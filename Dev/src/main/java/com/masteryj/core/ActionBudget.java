package com.masteryj.core;

/**
 * Shared ceiling on <b>synthetic</b> (mod-generated) click/use actions produced by AutoLeft
 * and AutoRight combined. Real vanilla input is never counted or throttled here — the budget
 * only ever limits actions the mod injects, so it can never delay or block the player's own
 * clicks. A denied action is <b>dropped</b>, never queued, so it can never burst later.
 *
 * <p>Single-threaded (client tick only): no locks, no threads, no sleep. All timing is passed
 * in as {@code now} (ms) so the caps are unit-testable without a Minecraft runtime.
 *
 * <p>Two independent caps:
 * <ul>
 *   <li>{@link #MAX_PER_TICK} — combined synthetic actions in one client tick. Each module
 *       already emits at most one pulse per tick (see {@link ClickScheduler}); this bounds the
 *       sum so a future caller can never reintroduce a per-tick burst.</li>
 *   <li>{@link #MAX_PER_SECOND} — combined synthetic actions per rolling second. Sits above any
 *       single module's CPS hard-cap (30) but below the dangerous doubled rate, so one module at
 *       full speed is untouched while two modules mashing at once can never sum to ~60/s.</li>
 * </ul>
 */
public final class ActionBudget {

    public static final int MAX_PER_TICK = 2;
    public static final int MAX_PER_SECOND = 40;
    private static final long WINDOW_MS = 1000L;
    private static final long TICK_MS = 50L;

    /** Shared production instance used by AutoLeft + AutoRight. Tests use {@code new}. */
    public static final ActionBudget INSTANCE = new ActionBudget();

    private long tickBucket = Long.MIN_VALUE;
    private int usedThisTick;
    private int maxInOneTick;
    private long windowStartMs = Long.MIN_VALUE;
    private int usedThisWindow;
    private long dropped;

    public ActionBudget() {
    }

    /** Reserve one synthetic action at {@code nowMs}. True if allowed; false (dropped) if a cap is hit. */
    public boolean tryConsume(long nowMs) {
        long bucket = Math.floorDiv(nowMs, TICK_MS);
        if (bucket != tickBucket) {
            tickBucket = bucket;
            usedThisTick = 0;
        }
        if (windowStartMs == Long.MIN_VALUE || nowMs - windowStartMs >= WINDOW_MS) {
            windowStartMs = nowMs;
            usedThisWindow = 0;
        }
        if (usedThisTick >= MAX_PER_TICK || usedThisWindow >= MAX_PER_SECOND) {
            dropped++;
            return false;
        }
        usedThisTick++;
        usedThisWindow++;
        if (usedThisTick > maxInOneTick) {
            maxInOneTick = usedThisTick;
        }
        return true;
    }

    /** Total synthetic actions dropped because a cap was hit (diagnostics only). */
    public long dropped() {
        return dropped;
    }

    /** Highest number of synthetic actions ever allowed within a single tick (diagnostics only). */
    public int maxInOneTick() {
        return maxInOneTick;
    }
}
