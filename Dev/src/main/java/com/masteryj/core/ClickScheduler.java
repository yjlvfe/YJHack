package com.masteryj.core;

/**
 * Tick-aware phase accumulator for synthetic clicks — the sole cadence source for AutoLeft
 * and AutoRight Block Mode. Emits <b>at most two</b> pulses per client tick and has <b>no
 * catch-up</b>.
 *
 * <p>Why an accumulator instead of a wall-clock deadline: at ~20 TPS a plain {@code now >=
 * nextAt} deadline is quantised by the tick grid. At 20 CPS (50&nbsp;ms target) a tick that
 * lands a hair early (49&nbsp;ms of jitter) misses its deadline and the click slips a whole
 * tick, so the felt rate sags toward ~10&nbsp;CPS and presses appear to be "eaten". This
 * accumulator is wall-time agnostic: every tick it adds a <b>fixed</b> {@code cps} to the
 * phase and drains whole pulses out of it, so jitter of a few milliseconds cannot drop the
 * rate and 40&nbsp;CPS reliably yields two pulses per tick.
 *
 * <p>Because the per-tick increment is a fixed {@code cps} — never a value derived from how
 * much wall-time elapsed — a client-thread stall simply means the tick loop was called fewer
 * times, so fewer pulses were produced. A stall can therefore <b>never</b> be replayed as a
 * burst (the old {@code while (now >= next) { emit(); next += delay; }} catch-up vector), and
 * missed time is never "compensated". Leftover phase is bounded below one pulse, and a full
 * pulse of backlog can never be carried into the next tick.
 *
 * <p>Single-threaded (client tick only): no locks, no threads, no allocation, integer maths.
 * Call {@link #pulsesThisTick(int)} exactly once per client tick per module.
 */
public final class ClickScheduler {

    /** Client tick rate; also the phase a module must accumulate to earn one pulse. */
    public static final int TICKS_PER_SECOND = 20;
    /** Hard ceiling: a single module may emit at most this many pulses in one tick. */
    public static final int MAX_PULSES_PER_TICK = 2;

    /** Progress toward the next pulse, in "clicks × ticks" units; always {@code 0..<TICKS_PER_SECOND} between ticks. */
    private int phase = 0;
    /** A fresh physical press guarantees the very next tick fires at least one pulse (immediate first click). */
    private boolean immediate = false;

    /**
     * Prime for an immediate first pulse on the next {@link #pulsesThisTick(int)} — a fresh
     * physical press (or the tick after leaving a mining hold). Cadence then starts clean.
     */
    public void armImmediate() {
        phase = 0;
        immediate = true;
    }

    /**
     * Cancel any accumulated phase and re-prime for an immediate first pulse. Used on
     * GUI open / focus loss / world null / disable / death / mouse-up. Because idle ticks
     * call this, the scheduler stays primed and the next real press fires with no delay.
     */
    public void clear() {
        phase = 0;
        immediate = true;
    }

    /**
     * Advance exactly one client tick at {@code cps} and return how many pulses to emit now
     * (0..{@link #MAX_PULSES_PER_TICK}).
     *
     * <ul>
     *   <li>1..20&nbsp;CPS → at most one pulse this tick.</li>
     *   <li>21..40&nbsp;CPS → up to two pulses on some ticks (40&nbsp;CPS → two every tick).</li>
     * </ul>
     *
     * The immediate flag can only lift a would-be 0 up to 1; it can never add a third pulse
     * on top of the natural cadence, so the {@link #MAX_PULSES_PER_TICK} ceiling is absolute.
     */
    public int pulsesThisTick(int cps) {
        if (cps < 0) {
            cps = 0;
        }
        phase += cps;
        int pulses = 0;
        while (phase >= TICKS_PER_SECOND && pulses < MAX_PULSES_PER_TICK) {
            pulses++;
            phase -= TICKS_PER_SECOND;
        }
        if (immediate) {
            immediate = false;
            if (pulses == 0) {
                pulses = 1;
                phase = 0;   // the immediate pulse consumes this tick's progress — no double soon after
            }
        }
        // No catch-up: a full pulse of backlog is never carried forward (defensive — with
        // cps <= 40 the drain above already leaves phase < TICKS_PER_SECOND).
        if (phase >= TICKS_PER_SECOND) {
            phase = TICKS_PER_SECOND - 1;
        }
        return pulses;
    }
}
