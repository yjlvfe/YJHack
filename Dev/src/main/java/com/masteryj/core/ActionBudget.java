package com.masteryj.core;

import com.masteryj.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Global fair budget for synthetic AutoLeft and AutoRight actions.
 *
 * <p>Modules submit requests late in a client tick. The dispatcher flushes previously submitted
 * work at END_CLIENT_TICK after vanilla input handling, and newly submitted requests remain for the
 * following tick. Guards are checked immediately before emission so queued actions cannot survive
 * menus, focus changes, releases, slot changes, or world transitions.
 *
 * <p>The contract is global, not per-module:
 * <ul>
 *   <li>at most two synthetic actions total in one client tick;</li>
 *   <li>at most forty synthetic actions total in any sliding one-second window;</li>
 *   <li>when both modules request actions, each receives one before either receives a second;</li>
 *   <li>denied actions are dropped and are never replayed later.</li>
 * </ul>
 * Real vanilla input never enters this budget.
 */
public final class ActionBudget {

    public static final int MAX_PER_TICK_GLOBAL = 2;
    public static final int MAX_PER_SECOND_GLOBAL = 40;
    private static final long WINDOW_NANOS = 1_000_000_000L;

    public enum Module { LEFT, RIGHT }
    private static final int MODULES = Module.values().length;

    public static final ActionBudget INSTANCE = new ActionBudget();

    private final int[] requested = new int[MODULES];
    private final BooleanSupplier[] guards = new BooleanSupplier[MODULES];
    private final Runnable[] emitters = new Runnable[MODULES];
    private final long[] dropped = new long[MODULES];

    private final long[] recent = new long[MAX_PER_SECOND_GLOBAL];
    private int recentCount;
    private int recentHead;
    private int preferredModule;
    private int maxInOneTick;

    /**
     * Submit up to two actions for the next dispatcher flush. Repeated submissions from the same
     * module are combined but still capped at two. The guard is re-checked immediately before
     * emission so a release, slot change, menu, focus loss, or world change cancels stale work.
     */
    public void request(Module module, int pulses, BooleanSupplier guard, Runnable emitter) {
        if (module == null || pulses <= 0 || guard == null || emitter == null) return;
        int m = module.ordinal();
        requested[m] = Math.min(MAX_PER_TICK_GLOBAL, requested[m] + pulses);
        guards[m] = guard;
        emitters[m] = emitter;
    }

    /** Cancel pending work for one module without clearing the global rolling-rate history. */
    public void cancel(Module module) {
        if (module == null) return;
        clearPending(module.ordinal());
    }

    /**
     * Test-compatible flush which uses the supplied emitter callback. Runtime code should call
     * {@link #flush(MinecraftClient, long)} so the exact vanilla click methods are invoked.
     */
    public void flush(long nowNanos) {
        flushInternal(null, nowNanos);
    }

    /**
     * Flush one real client tick using Minecraft's own private attack/use methods. Calling the
     * vanilla methods directly avoids the old KeyBinding queue race that could swallow a held
     * left or right click before Minecraft consumed it.
     */
    public void flush(MinecraftClient client, long nowNanos) {
        flushInternal(client, nowNanos);
    }

    private void flushInternal(MinecraftClient client, long nowNanos) {
        boolean[] valid = new boolean[MODULES];
        for (int m = 0; m < MODULES; m++) {
            if (requested[m] <= 0) continue;
            valid[m] = guards[m] != null && guards[m].getAsBoolean();
            if (!valid[m]) {
                notifyGateRejected(Module.values()[m], requested[m]);
                dropped[m] += requested[m];
                requested[m] = 0;
            }
        }

        int availableThisSecond = Math.max(0,
                MAX_PER_SECOND_GLOBAL - countWithinWindow(nowNanos));
        int remaining = Math.min(MAX_PER_TICK_GLOBAL, availableThisSecond);
        int[] granted = new int[MODULES];

        int first = preferredModule;
        int second = 1 - first;
        preferredModule = second;

        // Fair first pass: one action per requesting module.
        remaining = grantOne(first, valid, granted, remaining);
        remaining = grantOne(second, valid, granted, remaining);

        // If only one module requested, it may use the remaining global slot.
        while (remaining > 0) {
            int before = remaining;
            remaining = grantOne(first, valid, granted, remaining);
            if (remaining > 0) remaining = grantOne(second, valid, granted, remaining);
            if (remaining == before) break;
        }

        int emittedThisTick = 0;
        for (int m = 0; m < MODULES; m++) {
            int deniedByBudget = requested[m] - granted[m];
            if (deniedByBudget > 0) {
                dropped[m] += deniedByBudget;
                notifyBudgetRejected(Module.values()[m], deniedByBudget);
            }

            Module module = Module.values()[m];
            Runnable emitter = emitters[m];
            for (int i = 0; i < granted[m]; i++) {
                if (client == null) {
                    emitter.run();
                } else {
                    emitVanillaAction(client, module);
                }
                record(nowNanos);
                emittedThisTick++;
            }
            notifyTickPulses(module, granted[m]);
            clearPending(m);
        }
        maxInOneTick = Math.max(maxInOneTick, emittedThisTick);
    }

    private void emitVanillaAction(MinecraftClient client, Module module) {
        MinecraftClientInvoker invoker = (MinecraftClientInvoker) client;
        if (module == Module.LEFT) {
            invoker.yjhack$invokeDoAttack();
            DebugStats.onAutoLeftPulse();
        } else {
            invoker.yjhack$invokeDoItemUse();
            DebugStats.onAutoRightBlockPulse();
        }
    }

    private int grantOne(int module, boolean[] valid, int[] granted, int remaining) {
        if (remaining <= 0 || !valid[module] || granted[module] >= requested[module]) {
            return remaining;
        }
        granted[module]++;
        return remaining - 1;
    }

    private int countWithinWindow(long nowNanos) {
        int count = 0;
        for (int i = 0; i < recentCount; i++) {
            if (nowNanos - recent[i] < WINDOW_NANOS) count++;
        }
        return count;
    }

    private void record(long nowNanos) {
        recent[recentHead] = nowNanos;
        recentHead = (recentHead + 1) % MAX_PER_SECOND_GLOBAL;
        if (recentCount < MAX_PER_SECOND_GLOBAL) recentCount++;
    }

    private void clearPending(int module) {
        requested[module] = 0;
        guards[module] = null;
        emitters[module] = null;
    }

    /** Clear pending requests, rate history, and fairness state on a gameplay-session reset. */
    public void resetAll() {
        Arrays.fill(requested, 0);
        Arrays.fill(guards, null);
        Arrays.fill(emitters, null);
        Arrays.fill(recent, 0L);
        recentCount = 0;
        recentHead = 0;
        preferredModule = 0;
    }

    public long dropped(Module module) {
        return dropped[module.ordinal()];
    }

    public long dropped() {
        return dropped[0] + dropped[1];
    }

    public int maxInOneTick() {
        return maxInOneTick;
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
