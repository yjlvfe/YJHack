package com.masteryj.autoright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyMultiVersionPlacementPolicyTest {

    @Test
    void fixedTwentyEmitsOnePulseEveryTick() {
        LegacyMultiVersionPlacementPolicy policy = new LegacyMultiVersionPlacementPolicy();
        assertEquals(1, policy.pulsesThisTick(20, true, true, true, true));
        assertEquals(1, policy.pulsesThisTick(20, true, true, true, true));
        assertEquals(1, policy.pulsesThisTick(20, true, true, true, true));
    }

    @Test
    void fixedTenGetsResponsiveFirstPulseThenStableHalfRate() {
        LegacyMultiVersionPlacementPolicy policy = new LegacyMultiVersionPlacementPolicy();
        assertEquals(1, policy.pulsesThisTick(10, true, true, true, true));
        assertEquals(0, policy.pulsesThisTick(10, true, true, true, true));
        assertEquals(1, policy.pulsesThisTick(10, true, true, true, true));
        assertEquals(0, policy.pulsesThisTick(10, true, true, true, true));
        assertEquals(1, policy.pulsesThisTick(10, true, true, true, true));
    }

    @Test
    void fortyNeverExceedsTwoPulsesPerTick() {
        LegacyMultiVersionPlacementPolicy policy = new LegacyMultiVersionPlacementPolicy();
        for (int i = 0; i < 100; i++) {
            assertEquals(2, policy.pulsesThisTick(40, true, true, true, true));
        }
    }

    @Test
    void invalidCandidateDropsWorkAndReacquireIsImmediateWithoutBacklog() {
        LegacyMultiVersionPlacementPolicy policy = new LegacyMultiVersionPlacementPolicy();
        assertEquals(0, policy.pulsesThisTick(40, true, true, true, false));
        assertEquals(0, policy.pulsesThisTick(40, true, true, true, false));
        assertEquals(2, policy.pulsesThisTick(40, true, true, true, true));
        assertEquals(2, policy.pulsesThisTick(40, true, true, true, true));

        policy.clearRuntimeState();
        assertEquals(0, policy.pulsesThisTick(10, true, true, true, false));
        assertEquals(1, policy.pulsesThisTick(10, true, true, true, true));
    }

    @Test
    void inactiveOrReleasedInputClearsCadence() {
        LegacyMultiVersionPlacementPolicy policy = new LegacyMultiVersionPlacementPolicy();
        assertEquals(1, policy.pulsesThisTick(10, true, true, true, true));
        assertEquals(0, policy.pulsesThisTick(10, false, true, true, true));
        assertEquals(1, policy.pulsesThisTick(10, true, true, true, true));
        assertEquals(0, policy.pulsesThisTick(10, true, true, false, true));
    }

    @Test
    void blockToBlockCanContinueButOtherTransitionsCannot() {
        assertTrue(LegacyMultiVersionPlacementPolicy.canContinueAcrossSlotChange(
                RightClickPolicy.Kind.BLOCK, RightClickPolicy.Kind.BLOCK));
        assertFalse(LegacyMultiVersionPlacementPolicy.canContinueAcrossSlotChange(
                RightClickPolicy.Kind.BLOCK, RightClickPolicy.Kind.SINGLE_PRESS));
        assertFalse(LegacyMultiVersionPlacementPolicy.canContinueAcrossSlotChange(
                RightClickPolicy.Kind.BLOCK, RightClickPolicy.Kind.PASS_THROUGH));
        assertFalse(LegacyMultiVersionPlacementPolicy.canContinueAcrossSlotChange(
                RightClickPolicy.Kind.SINGLE_PRESS, RightClickPolicy.Kind.BLOCK));
    }
}
