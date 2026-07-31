package com.masteryj.core;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/** Optional lightweight diagnostics enabled only with {@code -Dyjhack.debug=true}. */
public final class DebugStats {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-Debug");
    public static final boolean ENABLED = Boolean.getBoolean("yjhack.debug");
    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L;

    private static long autoLeftPhysicalPresses;
    private static long autoLeftRequested;
    private static long autoLeftEmitted;
    private static long autoLeftRejected;
    private static int autoLeftCfgMin;
    private static int autoLeftCfgMax;

    private static long autoRightPhysicalPresses;
    private static long autoRightRequested;
    private static long autoRightEmitted;
    private static long autoRightRejected;
    private static int autoRightCfgMin;
    private static int autoRightCfgMax;

    private static long singlePressSuppressions;
    private static long sneakTransitions;
    private static long slotChanges;
    private static final Map<String, long[]> TICKS = new HashMap<>();
    private static long lastLogNanos = Long.MIN_VALUE;

    private DebugStats() {
    }

    public static void onAutoLeftPhysicalPress() { if (ENABLED) autoLeftPhysicalPresses++; }
    public static void onAutoLeftRequested(int count) { if (ENABLED) autoLeftRequested += Math.max(0, count); }
    public static void onAutoLeftPulse() { if (ENABLED) autoLeftEmitted++; }
    public static void onAutoLeftBudgetRejected() { if (ENABLED) autoLeftRejected++; }
    public static void onAutoLeftGateRejected() { if (ENABLED) autoLeftRejected++; }
    public static void onAutoLeftTickPulses(int pulses) { }
    public static void setAutoLeftConfiguredCps(int min, int max) {
        if (ENABLED) { autoLeftCfgMin = min; autoLeftCfgMax = max; }
    }

    public static void onAutoRightPhysicalPress() { if (ENABLED) autoRightPhysicalPresses++; }
    public static void onAutoRightRequested(int count) { if (ENABLED) autoRightRequested += Math.max(0, count); }
    public static void onAutoRightBlockPulse() { if (ENABLED) autoRightEmitted++; }
    public static void onAutoRightBudgetRejected() { if (ENABLED) autoRightRejected++; }
    public static void onAutoRightGateRejected() { if (ENABLED) autoRightRejected++; }
    public static void onAutoRightTickPulses(int pulses) { }
    public static void setAutoRightConfiguredCps(int min, int max) {
        if (ENABLED) { autoRightCfgMin = min; autoRightCfgMax = max; }
    }

    public static void onSinglePressSuppressed() { if (ENABLED) singlePressSuppressions++; }
    public static void onSneakTransition() { if (ENABLED) sneakTransitions++; }
    public static void onSlotChange() { if (ENABLED) slotChanges++; }

    public static ClientTickEvents.EndTick timed(String module, ClientTickEvents.EndTick delegate) {
        if (!ENABLED) return delegate;
        return client -> {
            long start = System.nanoTime();
            delegate.onEndTick(client);
            long now = System.nanoTime();
            record(module, now - start);
            maybeLog(now);
        };
    }

    private static void record(String module, long nanos) {
        long[] stats = TICKS.computeIfAbsent(module, key -> new long[3]);
        stats[0]++;
        stats[1] += nanos;
        stats[2] = Math.max(stats[2], nanos);
    }

    private static void maybeLog(long nowNanos) {
        if (lastLogNanos == Long.MIN_VALUE) {
            lastLogNanos = nowNanos;
            return;
        }
        if (nowNanos - lastLogNanos < LOG_INTERVAL_NANOS) return;
        lastLogNanos = nowNanos;

        LOGGER.info("[yjhack 10s] left cps={}-{} physical={} requested={} direct={} rejected={}",
                autoLeftCfgMin, autoLeftCfgMax, autoLeftPhysicalPresses,
                autoLeftRequested, autoLeftEmitted, autoLeftRejected);
        LOGGER.info("[yjhack 10s] right cps={}-{} physical={} requested={} direct={} rejected={}",
                autoRightCfgMin, autoRightCfgMax, autoRightPhysicalPresses,
                autoRightRequested, autoRightEmitted, autoRightRejected);
        LOGGER.info("[yjhack 10s] misc singlePress={} sneak={} slot={}",
                singlePressSuppressions, sneakTransitions, slotChanges);

        for (Map.Entry<String, long[]> entry : TICKS.entrySet()) {
            long[] stats = entry.getValue();
            if (stats[0] == 0) continue;
            LOGGER.info("[yjhack 10s] {} tick avg={}us max={}us",
                    entry.getKey(), stats[1] / stats[0] / 1000L, stats[2] / 1000L);
        }

        autoLeftPhysicalPresses = autoLeftRequested = autoLeftEmitted = autoLeftRejected = 0;
        autoRightPhysicalPresses = autoRightRequested = autoRightEmitted = autoRightRejected = 0;
        singlePressSuppressions = sneakTransitions = slotChanges = 0;
        TICKS.clear();
    }
}
