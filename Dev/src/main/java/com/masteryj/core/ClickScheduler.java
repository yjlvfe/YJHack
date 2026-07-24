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
 *
 * <p>Time-unit agnostic: {@code now} and {@code delay} just have to share one unit. In
 * production both are nanoseconds from the monotonic {@link System#nanoTime()} clock (so a
 * wall-clock jump can never make a pulse "due"); the unit tests drive it in milliseconds.
 * The "armed for an immediate pulse" state is {@link Long#MIN_VALUE}, so a fresh press
 * fires on the next tick even if the monotonic clock's origin is negative.
 */
public final class ClickScheduler {

    /** Next instant a pulse is allowed. {@link Long#MIN_VALUE} = armed for an immediate pulse. */
    private long nextAt = Long.MIN_VALUE;

    /** True when a pulse is due. The caller must invoke this at most once per client tick. */
    public boolean due(long now) {
        return now >= nextAt;
    }

    /**
     * Reschedule the next pulse to {@code now + delay} (absolute — drops any backlog).
     * Call this immediately after emitting a pulse. {@code delay} is in {@code now}'s unit.
     */
    public void rearm(long now, long delay) {
        nextAt = now + Math.max(1L, delay);
    }

    /** Arm for an immediate pulse on the next {@link #due(long)} (start of a fresh press). */
    public void armImmediate() {
        nextAt = Long.MIN_VALUE;
    }

    /** Cancel any pending pulse — used on GUI open / focus loss / world null / disable. */
    public void clear() {
        nextAt = Long.MIN_VALUE;
    }
}
