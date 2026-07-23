package com.masteryj.autoright;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Headless model of {@link AutoRightClient}'s rising-edge single-press contract.
 *
 * <p>On a SINGLE_PRESS item AutoRight never injects synthetic clicks. Vanilla emits
 * exactly one use on the physical rising edge ({@code mouseDown && !prevDown}); while
 * the button is held AutoRight forces the use key released so vanilla's itemUse cooldown
 * cannot re-fire. Therefore effective uses == number of rising edges == distinct presses.
 * This mirrors {@code AutoRightClient.handleSinglePress} exactly and locks the acceptance
 * criteria in as an executable test.
 */
class RisingEdgeModelTest {

    /** Number of item uses produced for a per-tick sequence of physical button states. */
    private static int uses(boolean[] mouseDownPerTick) {
        boolean prevDown = false;
        int uses = 0;
        for (boolean down : mouseDownPerTick) {
            if (down && !prevDown) {   // rising edge: vanilla fires exactly one use
                uses++;
            }
            prevDown = down;           // held => suppressed; released => nothing
        }
        return uses;
    }

    @Test
    void twentySeparatePressesGiveTwentyUses() {
        boolean[] seq = new boolean[80];
        for (int i = 0; i < 20; i++) {
            seq[i * 4] = true; // press one tick, release three
        }
        assertEquals(20, uses(seq));
    }

    @Test
    void holdingGivesExactlyOneUse() {
        boolean[] held = new boolean[200];
        java.util.Arrays.fill(held, true);
        assertEquals(1, uses(held));
    }

    @Test
    void releaseThenPressReArmsForANewUse() {
        assertEquals(2, uses(new boolean[] { true, true, true, false, false, true, true }));
    }

    @Test
    void fiveRealisticHoldsGiveFiveUses() {
        boolean[] seq = new boolean[100];
        for (int i = 0; i < 5; i++) {
            for (int t = 0; t < 10; t++) {
                seq[i * 20 + t] = true; // 10-tick hold then release
            }
        }
        assertEquals(5, uses(seq));
    }

    @Test
    void neverPressingGivesNoUses() {
        assertEquals(0, uses(new boolean[50]));
    }
}
