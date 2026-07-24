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
 * and pays effectively nothing. It logs an aggregate summary at most once every 10 s — never
 * per-packet or per-tick — and records no player data and no packet contents.
 */
public final class DebugStats {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-Debug");

    /** Off unless the process was started with {@code -Dyjhack.debug=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("yjhack.debug");

    private static final long LOG_INTERVAL_MS = 10_000L;

    // Synthetic-action / state-change counters (per 10 s window).
    private static long autoLeftPulses;
    private static long autoRightBlockPulses;
    private static long singlePressSuppressions;
    private static long sneakTransitions;
    private static long slotChanges;
    private static long droppedBacklog;

    // Per-module tick timing: name -> {count, totalNanos, maxNanos, over50ms, over100ms}.
    private static final Map<String, long[]> TICKS = new HashMap<>();
    private static long lastLogMs;

    private DebugStats() {
    }

    public static void onAutoLeftPulse() {
        if (ENABLED) autoLeftPulses++;
    }

    public static void onAutoRightBlockPulse() {
        if (ENABLED) autoRightBlockPulses++;
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

    public static void onDroppedBacklog() {
        if (ENABLED) droppedBacklog++;
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
            maybeLog(System.currentTimeMillis());
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

    private static void maybeLog(long now) {
        if (now - lastLogMs < LOG_INTERVAL_MS) {
            return;
        }
        lastLogMs = now;
        LOGGER.info("[yjhack 10s] left={} rightBlock={} spSuppress={} sneak={} slot={} dropped={} maxActions/tick={}",
                autoLeftPulses, autoRightBlockPulses, singlePressSuppressions,
                sneakTransitions, slotChanges, droppedBacklog, ActionBudget.INSTANCE.maxInOneTick());
        for (Map.Entry<String, long[]> e : TICKS.entrySet()) {
            long[] s = e.getValue();
            if (s[0] == 0) {
                continue;
            }
            LOGGER.info("[yjhack 10s] {} tick avg={}us max={}us >50ms={} >100ms={}",
                    e.getKey(), s[1] / s[0] / 1000L, s[2] / 1000L, s[3], s[4]);
        }
        autoLeftPulses = autoRightBlockPulses = singlePressSuppressions = 0;
        sneakTransitions = slotChanges = droppedBacklog = 0;
        TICKS.clear();
    }
}
