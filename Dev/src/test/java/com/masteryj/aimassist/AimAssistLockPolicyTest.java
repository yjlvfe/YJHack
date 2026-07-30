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
    void blockActionsCancelAnExistingLock() {
        assertTrue(AimAssistClient.shouldCancelForBlockAction(true, false, false),
                "active block breaking cancels the lock");
        assertTrue(AimAssistClient.shouldCancelForBlockAction(false, true, false),
                "starting a block attack cancels the lock");
        assertTrue(AimAssistClient.shouldCancelForBlockAction(false, false, true),
                "placing a block cancels the lock");
        assertFalse(AimAssistClient.shouldCancelForBlockAction(false, false, false),
                "ordinary combat movement keeps the same visible target latched");
    }
}
