package com.masteryj.core;

/**
 * One-pulse-per-tick scheduler for synthetic clicks, with <b>no catch-up</b>.
 *
 * <p>The old AutoLeft/AutoRight loops drained the whole backlog inside a single tick
 * (`while (now >= next) { emit(); next += delay; }`, up to 50 pulses). If the client
 * thread stalled, the missed clicks were replayed as one burst on the next tick, which
 * is exactly the packet-burst → proxy/disconnect vector we must remove.
 *
 * <p>This scheduler emits <b>at most one</b> pulse per {@link #due(long)} check and, when
 * it fires, reschedules the next pulse from the current time ({@code now + delay}, never
 * {@code next += delay}). A late tick therefore drops the backlog instead of bursting.
 * Pure state + time in/out, so the guarantee is unit-testable with no Minecraft runtime.
 */
public final class ClickScheduler {

    /** Next time a pulse is allowed. 0 = armed for an immediate first pulse. */
    private long nextAtMs = 0L;

    /** True when a pulse is due. The caller must invoke this at most once per client tick. */
    public boolean due(long nowMs) {
        return nowMs >= nextAtMs;
    }

    /**
     * Reschedule the next pulse to {@code now + delay} (absolute — drops any backlog).
     * Call this immediately after emitting a pulse.
     */
    public void rearm(long nowMs, int delayMs) {
        nextAtMs = nowMs + Math.max(1, delayMs);
    }

    /** Arm for an immediate pulse on the next {@link #due(long)} (start of a fresh press). */
    public void armImmediate() {
        nextAtMs = 0L;
    }

    /** Cancel any pending pulse — used on GUI open / focus loss / world null / disable. */
    public void clear() {
        nextAtMs = 0L;
    }
}
