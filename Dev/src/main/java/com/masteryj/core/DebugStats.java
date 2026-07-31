package com.masteryj.core;

import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in, near-zero-cost diagnostics. Enable with {@code -Dyjhack.debug=true}.
 *
 * <p>Logs aggregate data at most once every ten seconds. "emit" means a synthetic configured-key
 * press was queued for vanilla input handling; Minecraft and the server remain authoritative for
 * whether the resulting interaction is accepted.
 */
public final class DebugStats {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-Debug");
    public static final boolean ENABLED = Boolean.getBoolean("yjhack.debug");
    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L;

    private static long autoLeftPhysicalPresses;
    private static long autoLeftRequested;
    private static long autoLeftEmitted;
    private static long autoLeftBudgetRejected;
    private static long autoLeftGateRejected;
    private static int autoLeftMaxPulsesPerTick;
    private static int autoLeftCfgMin;
    private static int autoLeftCfgMax;

    private static long autoRightPhysicalPresses;
    private static long autoRightRequested;
    private static long autoRightBlockEmitted;
    private static long autoRightBudgetRejected;
    private static long autoRightGateRejected;
    private static int autoRightMaxPulsesPerTick;
    private static int autoRightCfgMin;
    private static int autoRightCfgMax;

    private static long singlePressSuppressions;
    private static long sneakTransitions;
    private static long slotChanges;

    // module -> {count, totalNanos, maxNanos, over50ms, over100ms}
    private static final Map<String, long[]> TICKS = new HashMap<>();
    private static long lastLogNanos = Long.MIN_VALUE;

    private DebugStats() {
    }

    public static void onAutoLeftPhysicalPress() {
        if (ENABLED) autoLeftPhysicalPresses++;
    }

    public static void onAutoLeftRequested(int count) {
        if (ENABLED) autoLeftRequested += Math.max(0, count);
    }

    public static void onAutoLeftPulse() {
        if (ENABLED) autoLeftEmitted++;
    }

    public static void onAutoLeftBudgetRejected() {
        if (ENABLED) autoLeftBudgetRejected++;
    }

    public static void onAutoLeftGateRejected() {
        if (ENABLED) autoLeftGateRejected++;
    }

    public static void onAutoLeftTickPulses(int pulses) {
        if (ENABLED && pulses > autoLeftMaxPulsesPerTick) autoLeftMaxPulsesPerTick = pulses;
    }

    public static void setAutoLeftConfiguredCps(int min, int max) {
        if (ENABLED) {
            autoLeftCfgMin = min;
            autoLeftCfgMax = max;
        }
    }

    public static void onAutoRightPhysicalPress() {
        if (ENABLED) autoRightPhysicalPresses++;
    }

    public static void onAutoRightRequested(int count) {
        if (ENABLED) autoRightRequested += Math.max(0, count);
    }

    public static void onAutoRightBlockPulse() {
        if (ENABLED) autoRightBlockEmitted++;
    }

    public static void onAutoRightBudgetRejected() {
        if (ENABLED) autoRightBudgetRejected++;
    }

    public static void onAutoRightGateRejected() {
        if (ENABLED) autoRightGateRejected++;
    }

    public static void onAutoRightTickPulses(int pulses) {
        if (ENABLED && pulses > autoRightMaxPulsesPerTick) autoRightMaxPulsesPerTick = pulses;
    }

    public static void setAutoRightConfiguredCps(int min, int max) {
        if (ENABLED) {
            autoRightCfgMin = min;
            autoRightCfgMax = max;
        }
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

    public static ClientTickEvents.EndTick timed(String module, ClientTickEvents.EndTick delegate) {
        if (!ENABLED) return delegate;
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
        if (nanos > s[2]) s[2] = nanos;
        long ms = nanos / 1_000_000L;
        if (ms >= 50) s[3]++;
        if (ms >= 100) s[4]++;
    }

    private static void maybeLog(long nowNanos) {
        if (lastLogNanos == Long.MIN_VALUE) {
            lastLogNanos = nowNanos;
            return;
        }
        if (nowNanos - lastLogNanos < LOG_INTERVAL_NANOS) return;
        lastLogNanos = nowNanos;

        LOGGER.info("[yjhack 10s] AutoLeft cps={}-{} presses={} req={} emit={} budgetRej={} gateRej={} maxPulses/tick={}",
                autoLeftCfgMin, autoLeftCfgMax, autoLeftPhysicalPresses, autoLeftRequested,
                autoLeftEmitted, autoLeftBudgetRejected, autoLeftGateRejected,
                autoLeftMaxPulsesPerTick);
        LOGGER.info("[yjhack 10s] AutoRight cps={}-{} presses={} req={} emit={} budgetRej={} gateRej={} maxPulses/tick={}",
                autoRightCfgMin, autoRightCfgMax, autoRightPhysicalPresses, autoRightRequested,
                autoRightBlockEmitted, autoRightBudgetRejected, autoRightGateRejected,
                autoRightMaxPulsesPerTick);
        LOGGER.info("[yjhack 10s] misc spSuppress={} sneak={} slot={} globalMaxActions/tick={}",
                singlePressSuppressions, sneakTransitions, slotChanges, ActionBudget.INSTANCE.maxInOneTick());

        for (Map.Entry<String, long[]> e : TICKS.entrySet()) {
            long[] s = e.getValue();
            if (s[0] == 0) continue;
            LOGGER.info("[yjhack 10s] {} tick avg={}us max={}us >50ms={} >100ms={}",
                    e.getKey(), s[1] / s[0] / 1000L, s[2] / 1000L, s[3], s[4]);
        }

        autoLeftPhysicalPresses = autoLeftRequested = autoLeftEmitted = 0;
        autoLeftBudgetRejected = autoLeftGateRejected = 0;
        autoRightPhysicalPresses = autoRightRequested = autoRightBlockEmitted = 0;
        autoRightBudgetRejected = autoRightGateRejected = 0;
        autoLeftMaxPulsesPerTick = autoRightMaxPulsesPerTick = 0;
        singlePressSuppressions = sneakTransitions = slotChanges = 0;
        TICKS.clear();
    }
}
