package com.masteryj.core;

/**
 * Shared ceiling on <b>synthetic</b> (mod-generated) click/use actions from AutoLeft and
 * AutoRight. Real vanilla input is never counted or throttled here — the budget only ever
 * limits actions the mod injects, so it can never delay or block the player's own clicks.
 * A denied action is <b>dropped</b>, never queued, so it can never burst later.
 *
 * <p>Each module has its <b>own independent quota</b>, so the order the two tick callbacks
 * run in cannot let one module starve the other: whoever asks first never consumes the
 * other's allowance. Two caps per module:
 * <ul>
 *   <li>{@link #MAX_PER_TICK_PER_MODULE} = 2 — at most two synthetic actions per module per
 *       client tick, matching the {@link ClickScheduler} two-pulses-per-tick cadence ceiling
 *       (~20 TPS ⇒ up to ~40 CPS).</li>
 *   <li>{@link #MAX_PER_SECOND_PER_MODULE} = 40 — a <b>sliding</b> one-second window (not a
 *       fixed window), so a batch can never straddle a second boundary and double up.</li>
 * </ul>
 *
 * <p>This is a secondary safety net, not the rate source: the phase accumulator already caps
 * the cadence at two pulses per tick, so at a steady 40&nbsp;CPS on a normal 20&nbsp;TPS
 * client the budget rejects nothing. A rejection means a genuine over-rate — a duplicate
 * click path or a third pulse in one tick — which is exactly what we want caught.
 *
 * <p>Single-threaded (client tick only): no locks, no threads, no sleep, no allocation on the
 * hot path. All timing is passed in as {@code nowNanos} from the monotonic
 * {@link System#nanoTime()} clock so the caps are unit-testable without a Minecraft runtime
 * and are immune to a system-clock jump.
 */
public final class ActionBudget {

    /** At most two synthetic actions per module per client tick. */
    public static final int MAX_PER_TICK_PER_MODULE = 2;
    /** Per-module sliding-second cap; equals the 40 CPS ceiling. */
    public static final int MAX_PER_SECOND_PER_MODULE = 40;

    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final long TICK_NANOS = 50_000_000L;

    /** The two synthetic-action producers that share (independently) this budget. */
    public enum Module { LEFT, RIGHT }
    private static final int MODULES = 2;

    /** Shared production instance used by AutoLeft + AutoRight. Tests use {@code new}. */
    public static final ActionBudget INSTANCE = new ActionBudget();

    // Per-module sliding window: a ring of the most recent action timestamps (nanos).
    private final long[][] recent = new long[MODULES][MAX_PER_SECOND_PER_MODULE];
    private final int[] recentCount = new int[MODULES];
    private final int[] recentHead = new int[MODULES];
    private final long[] lastTickBucket = new long[MODULES];
    private final int[] tickUsed = new int[MODULES];       // synthetic actions used in the current tick bucket
    private final long[] dropped = new long[MODULES];

    // Diagnostic only: highest combined synthetic actions observed within one tick.
    private long globalTickBucket = Long.MIN_VALUE;
    private int usedThisGlobalTick;
    private int maxInOneTick;

    public ActionBudget() {
        for (int m = 0; m < MODULES; m++) {
            lastTickBucket[m] = Long.MIN_VALUE;
        }
    }

    /**
     * Reserve one synthetic action for {@code module} at monotonic {@code nowNanos}.
     * True if allowed; false (dropped, never queued) if the module's per-tick or
     * sliding-second cap is hit.
     */
    public boolean tryConsume(Module module, long nowNanos) {
        int m = module.ordinal();
        long bucket = Math.floorDiv(nowNanos, TICK_NANOS);
        if (lastTickBucket[m] != bucket) {                        // entered a new tick: reset per-tick counter
            lastTickBucket[m] = bucket;
            tickUsed[m] = 0;
        }
        if (tickUsed[m] >= MAX_PER_TICK_PER_MODULE) {             // per-tick cap: 2 / module
            dropped[m]++;
            return false;
        }
        if (countWithinWindow(m, nowNanos) >= MAX_PER_SECOND_PER_MODULE) {   // sliding second
            dropped[m]++;
            return false;
        }
        tickUsed[m]++;
        recent[m][recentHead[m]] = nowNanos;
        recentHead[m] = (recentHead[m] + 1) % MAX_PER_SECOND_PER_MODULE;
        if (recentCount[m] < MAX_PER_SECOND_PER_MODULE) {
            recentCount[m]++;
        }
        if (bucket != globalTickBucket) {
            globalTickBucket = bucket;
            usedThisGlobalTick = 0;
        }
        usedThisGlobalTick++;
        if (usedThisGlobalTick > maxInOneTick) {
            maxInOneTick = usedThisGlobalTick;
        }
        return true;
    }

    private int countWithinWindow(int m, long nowNanos) {
        int c = 0;
        for (int i = 0; i < recentCount[m]; i++) {
            if (nowNanos - recent[m][i] < WINDOW_NANOS) {
                c++;
            }
        }
        return c;
    }

    /**
     * Clear one module's live rate window — world change / disconnect / GUI open / focus loss
     * / disable / death. Per-module so resetting an idle module never wipes the other's window.
     * Cumulative diagnostics ({@link #dropped}, {@link #maxInOneTick}) are intentionally kept.
     */
    public void reset(Module module) {
        int m = module.ordinal();
        recentCount[m] = 0;
        recentHead[m] = 0;
        lastTickBucket[m] = Long.MIN_VALUE;
        tickUsed[m] = 0;
    }

    /** Total synthetic actions dropped for one module because a cap was hit (diagnostics). */
    public long dropped(Module module) {
        return dropped[module.ordinal()];
    }

    /** Total synthetic actions dropped across both modules (diagnostics / tests). */
    public long dropped() {
        return dropped[0] + dropped[1];
    }

    /** Highest number of synthetic actions ever allowed within a single tick (diagnostics). */
    public int maxInOneTick() {
        return maxInOneTick;
    }
}
