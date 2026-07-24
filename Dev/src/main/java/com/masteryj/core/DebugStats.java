package com.masteryj.core;

import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in, near-zero-cost diagnostics for the synthetic-action paths. <b>Disabled by default</b>;
 * enable only with the JVM flag {@code -Dyjhack.debug=true} (never on in a normal Release run).
 *
 * <p>When disabled every counter method is a single predictable {@code if (ENABLED)} branch and
 * {@link #timed} returns the delegate unchanged, so the release path is behaviourally identical
 * and pays effectively nothing — no strings, no map updates, no allocation, no timing wrapper.
 * When enabled it logs an aggregate summary at most once every 10 s (measured on the monotonic
 * {@link System#nanoTime()} clock) — never per-tick, per-click or per-packet — and records no
 * player data and no packet contents. A dropped pulse is, by construction (no catch-up), exactly
 * a budget rejection, so "budget rejected" doubles as the backlog-dropped count.
 */
public final class DebugStats {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-Debug");

    /** Off unless the process was started with {@code -Dyjhack.debug=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("yjhack.debug");

    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L;

    // Synthetic-action / state-change counters (per 10 s window).
    private static long autoLeftEmitted;
    private static long autoLeftBudgetRejected;
    private static long autoRightBlockEmitted;
    private static long autoRightBudgetRejected;
    private static long singlePressSuppressions;
    private static long sneakTransitions;
    private static long slotChanges;

    // Per-module tick timing: name -> {count, totalNanos, maxNanos, over50ms, over100ms}.
    private static final Map<String, long[]> TICKS = new HashMap<>();
    private static long lastLogNanos = Long.MIN_VALUE;

    private DebugStats() {
    }

    public static void onAutoLeftPulse() {
        if (ENABLED) autoLeftEmitted++;
    }

    public static void onAutoLeftBudgetRejected() {
        if (ENABLED) autoLeftBudgetRejected++;
    }

    public static void onAutoRightBlockPulse() {
        if (ENABLED) autoRightBlockEmitted++;
    }

    public static void onAutoRightBudgetRejected() {
        if (ENABLED) autoRightBudgetRejected++;
    }

    public static void onSinglePressSuppressed() {
        if (ENABLED) singlePressSuppressions++;
    }

    public static void onSneakTransition() {
        if (ENABLED) sneakTransitions++;
    }

    public static void onSlotChange() {
        if (ENABLED) slotChanges++;
    }

    /**
     * Wrap a per-tick callback with timing. Returns the delegate <b>unchanged</b> when disabled,
     * so registration and runtime are byte-for-byte the release behaviour with debug off.
     */
    public static ClientTickEvents.EndTick timed(String module, ClientTickEvents.EndTick delegate) {
        if (!ENABLED) {
            return delegate;
        }
        return client -> {
            long start = System.nanoTime();
            delegate.onEndTick(client);
            record(module, System.nanoTime() - start);
            maybeLog(start);
        };
    }

    private static void record(String module, long nanos) {
        long[] s = TICKS.computeIfAbsent(module, k -> new long[5]);
        s[0]++;
        s[1] += nanos;
        if (nanos > s[2]) {
            s[2] = nanos;
        }
        long ms = nanos / 1_000_000L;
        if (ms >= 50) s[3]++;
        if (ms >= 100) s[4]++;
    }

    private static void maybeLog(long nowNanos) {
        if (lastLogNanos == Long.MIN_VALUE) {
            lastLogNanos = nowNanos;   // first tick: start the window, don't log zeros
            return;
        }
        if (nowNanos - lastLogNanos < LOG_INTERVAL_NANOS) {
            return;
        }
        lastLogNanos = nowNanos;
        // requested == emitted + budgetRejected (no separate backlog exists without catch-up).
        LOGGER.info("[yjhack 10s] AutoLeft req/emit/budgetRej={}/{}/{} AutoRight req/emit/budgetRej={}/{}/{} spSuppress={} sneak={} slot={} maxActions/tick={}",
                autoLeftEmitted + autoLeftBudgetRejected, autoLeftEmitted, autoLeftBudgetRejected,
                autoRightBlockEmitted + autoRightBudgetRejected, autoRightBlockEmitted, autoRightBudgetRejected,
                singlePressSuppressions, sneakTransitions, slotChanges, ActionBudget.INSTANCE.maxInOneTick());
        for (Map.Entry<String, long[]> e : TICKS.entrySet()) {
            long[] s = e.getValue();
            if (s[0] == 0) {
                continue;
            }
            LOGGER.info("[yjhack 10s] {} tick avg={}us max={}us >50ms={} >100ms={}",
                    e.getKey(), s[1] / s[0] / 1000L, s[2] / 1000L, s[3], s[4]);
        }
        autoLeftEmitted = autoLeftBudgetRejected = autoRightBlockEmitted = autoRightBudgetRejected = 0;
        singlePressSuppressions = sneakTransitions = slotChanges = 0;
        TICKS.clear();
    }
}
