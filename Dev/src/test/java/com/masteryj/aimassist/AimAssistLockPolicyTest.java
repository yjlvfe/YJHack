package com.masteryj.aimassist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AimAssistLockPolicyTest {

    @Test
    void lockDistanceEndsImmediatelyAfterFivePointFiveBlocks() {
        assertTrue(AimAssistRangePolicy.isWithinDistance(0.0D));
        assertTrue(AimAssistRangePolicy.isWithinDistance(30.25D));
        assertFalse(AimAssistRangePolicy.isWithinDistance(30.250_001D));
        assertFalse(AimAssistRangePolicy.isWithinDistance(Double.NaN));
        assertFalse(AimAssistRangePolicy.isWithinDistance(Double.POSITIVE_INFINITY));
    }

    @Test
    void losingLineOfSightDropsAnExistingLock() {
        assertTrue(AimAssistRangePolicy.shouldDropLock(true, false));
        assertFalse(AimAssistRangePolicy.shouldDropLock(true, true));
        assertFalse(AimAssistRangePolicy.shouldDropLock(false, false));
    }

    @Test
    void bedLockPersistsOnlyWhileTheSameBreakIsActive() {
        assertTrue(AimAssistClient.shouldHoldBedAimLock(
                true, true, true, true, false));
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                true, false, true, true, false), "releasing attack unlocks aim");
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                true, true, false, true, false), "ending block breaking unlocks aim");
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                true, true, true, false, false), "a destroyed bed unlocks aim");
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                true, true, true, true, true), "an accepted player hit overrides the bed lock");
    }

    @Test
    void ordinaryBlocksNeverCreateTheSpecialLock() {
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                false, true, true, true, false));
    }
}
