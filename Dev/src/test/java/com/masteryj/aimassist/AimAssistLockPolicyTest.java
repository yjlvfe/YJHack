package com.masteryj.aimassist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AimAssistLockPolicyTest {

    @Test
    void absoluteRangeEndsImmediatelyAfterThreePointFiveBlocks() {
        assertTrue(AimAssistRangePolicy.isWithinAbsoluteDistance(0.0D));
        assertTrue(AimAssistRangePolicy.isWithinAbsoluteDistance(12.25D));
        assertFalse(AimAssistRangePolicy.isWithinAbsoluteDistance(12.250_001D));
        assertFalse(AimAssistRangePolicy.isWithinAbsoluteDistance(Double.NaN));
        assertFalse(AimAssistRangePolicy.isWithinAbsoluteDistance(Double.POSITIVE_INFINITY));
    }

    @Test
    void configuredRangeCanNeverExceedThreePointFive() {
        assertEquals(3.5D, AimAssistRangePolicy.clampConfiguredDistance(999.0D), 0.0D);
        assertEquals(3.5D, AimAssistRangePolicy.clampConfiguredDistance(Double.NaN), 0.0D);
        assertTrue(AimAssistClient.isWithinLockDistance(12.25D, 999.0D));
        assertFalse(AimAssistClient.isWithinLockDistance(12.250_001D, 999.0D));
    }

    @Test
    void losingLineOfSightDropsAnExistingLock() {
        assertTrue(AimAssistRangePolicy.shouldDropLock(true, false));
        assertFalse(AimAssistRangePolicy.shouldDropLock(true, true));
        assertFalse(AimAssistRangePolicy.shouldDropLock(false, false));
    }

    @Test
    void bedLockPersistsOnlyWhileTheSameBedBreakIsActive() {
        assertTrue(AimAssistClient.shouldHoldBedAimLock(
                true, true, true, true, true));
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                true, false, true, true, true), "releasing attack unlocks aim");
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                true, true, false, true, true), "ending block breaking unlocks aim");
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                true, true, true, false, true), "a destroyed bed unlocks aim");
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                true, true, true, true, false), "changing away from the bed unlocks aim");
    }

    @Test
    void ordinaryBlocksNeverCreateTheSpecialLock() {
        assertFalse(AimAssistClient.shouldHoldBedAimLock(
                false, true, true, true, true));
    }
}
