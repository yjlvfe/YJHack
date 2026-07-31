package com.masteryj.core;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Independent, tick-safe budgets for synthetic AutoLeft and AutoRight input events.
 *
 * <p>Each module may enqueue at most one synthetic input event per client tick and at most twenty
 * events in its own rolling one-second window. The modules never borrow quota from one another,
 * so holding both buttons cannot starve either side. Denied work is dropped immediately and is
 * never replayed after lag, a menu, a world transition, or a later release.
 *
 * <p>Runtime emitters enqueue the player's configured Minecraft key binding. The dispatcher runs
 * at START_CLIENT_TICK, before vanilla consumes key presses, so Minecraft remains authoritative for
 * attack/use cooldowns, interaction sequencing, prediction, and packets.
 */
public final class ActionBudget {

    public static final int MAX_PER_TICK_PER_MODULE = 1;
    public static final int MAX_PER_SECOND_PER_MODULE = 20;
    private static final long WINDOW_NANOS = 1_000_000_000L;

    public enum Module { LEFT, RIGHT }
    private static final int MODULES = Module.values().length;

    public static final ActionBudget INSTANCE = new ActionBudget();

    private final int[] requested = new int[MODULES];
    private final BooleanSupplier[] guards = new BooleanSupplier[MODULES];
    private final Runnable[] emitters = new Runnable[MODULES];
    private final long[] dropped = new long[MODULES];

    private final long[][] recent = new long[MODULES][MAX_PER_SECOND_PER_MODULE];
    private final int[] recentCount = new int[MODULES];
    private final int[] recentHead = new int[MODULES];
    private final int[] maxPerModuleInOneTick = new int[MODULES];
    private int maxGlobalInOneTick;

    /** Submit synthetic input for the next START_CLIENT_TICK flush. */
    public void request(Module module, int pulses, BooleanSupplier guard, Runnable emitter) {
        if (module == null || pulses <= 0 || guard == null || emitter == null) return;
        int m = module.ordinal();
        int accepted = requested[m] == 0 ? 1 : 0;
        int rejected = pulses - accepted;

        if (accepted == 1) {
            requested[m] = 1;
            guards[m] = guard;
            emitters[m] = emitter;
            notifyRequested(module, 1);
        }
        if (rejected > 0) {
            dropped[m] += rejected;
            notifyBudgetRejected(module, rejected);
        }
    }

    /** Cancel pending work while preserving rolling rate history. */
    public void cancel(Module module) {
        if (module == null) return;
        clearPending(module.ordinal());
    }

    /** Clear one module's pending work, rate history, and diagnostic state. */
    public void reset(Module module) {
        if (module == null) return;
        int m = module.ordinal();
        clearPending(m);
        Arrays.fill(recent[m], 0L);
        recentCount[m] = 0;
        recentHead[m] = 0;
        dropped[m] = 0L;
        maxPerModuleInOneTick[m] = 0;
        maxGlobalInOneTick = Math.max(maxPerModuleInOneTick[0], maxPerModuleInOneTick[1]);
    }

    /** Flush queued input before vanilla's client-tick input handling. */
    public void flush(long nowNanos) {
        int emittedGlobal = 0;
        for (int m = 0; m < MODULES; m++) {
            if (requested[m] <= 0) continue;

            Module module = Module.values()[m];
            boolean valid = guards[m] != null && guards[m].getAsBoolean();
            if (!valid) {
                dropped[m] += requested[m];
                notifyGateRejected(module, requested[m]);
                clearPending(m);
                continue;
            }

            int emitted = 0;
            if (countWithinWindow(m, nowNanos) < MAX_PER_SECOND_PER_MODULE) {
                emitters[m].run();
                notifyEmitted(module);
                record(m, nowNanos);
                emitted = 1;
                emittedGlobal++;
            } else {
                dropped[m] += requested[m];
                notifyBudgetRejected(module, requested[m]);
            }

            notifyTickPulses(module, emitted);
            maxPerModuleInOneTick[m] = Math.max(maxPerModuleInOneTick[m], emitted);
            clearPending(m);
        }
        maxGlobalInOneTick = Math.max(maxGlobalInOneTick, emittedGlobal);
    }

    private int countWithinWindow(int module, long nowNanos) {
        int count = 0;
        for (int i = 0; i < recentCount[module]; i++) {
            if (nowNanos - recent[module][i] < WINDOW_NANOS) count++;
        }
        return count;
    }

    private void record(int module, long nowNanos) {
        recent[module][recentHead[module]] = nowNanos;
        recentHead[module] = (recentHead[module] + 1) % MAX_PER_SECOND_PER_MODULE;
        if (recentCount[module] < MAX_PER_SECOND_PER_MODULE) recentCount[module]++;
    }

    private void clearPending(int module) {
        requested[module] = 0;
        guards[module] = null;
        emitters[module] = null;
    }

    /** Clear all gameplay-session state. */
    public void resetAll() {
        Arrays.fill(requested, 0);
        Arrays.fill(guards, null);
        Arrays.fill(emitters, null);
        Arrays.fill(dropped, 0L);
        Arrays.fill(recentCount, 0);
        Arrays.fill(recentHead, 0);
        Arrays.fill(maxPerModuleInOneTick, 0);
        for (long[] moduleHistory : recent) Arrays.fill(moduleHistory, 0L);
        maxGlobalInOneTick = 0;
    }

    public long dropped(Module module) {
        return dropped[module.ordinal()];
    }

    public long dropped() {
        return dropped[0] + dropped[1];
    }

    public int maxInOneTick(Module module) {
        return maxPerModuleInOneTick[module.ordinal()];
    }

    public int maxInOneTick() {
        return maxGlobalInOneTick;
    }

    private static void notifyRequested(Module module, int count) {
        if (module == Module.LEFT) DebugStats.onAutoLeftRequested(count);
        else DebugStats.onAutoRightRequested(count);
    }

    private static void notifyEmitted(Module module) {
        if (module == Module.LEFT) DebugStats.onAutoLeftPulse();
        else DebugStats.onAutoRightBlockPulse();
    }

    private static void notifyBudgetRejected(Module module, int count) {
        for (int i = 0; i < count; i++) {
            if (module == Module.LEFT) DebugStats.onAutoLeftBudgetRejected();
            else DebugStats.onAutoRightBudgetRejected();
        }
    }

    private static void notifyGateRejected(Module module, int count) {
        for (int i = 0; i < count; i++) {
            if (module == Module.LEFT) DebugStats.onAutoLeftGateRejected();
            else DebugStats.onAutoRightGateRejected();
        }
    }

    private static void notifyTickPulses(Module module, int count) {
        if (module == Module.LEFT) DebugStats.onAutoLeftTickPulses(count);
        else DebugStats.onAutoRightTickPulses(count);
    }
}
