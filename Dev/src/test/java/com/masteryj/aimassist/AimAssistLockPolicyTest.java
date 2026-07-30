package com.masteryj.aimassist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AimAssistLockPolicyTest {

    @Test
    void lockDistanceEndsImmediatelyAfterFourBlocks() {
        assertTrue(AimAssistClient.isWithinLockDistance(0.0D));
        assertTrue(AimAssistClient.isWithinLockDistance(16.0D));
        assertFalse(AimAssistClient.isWithinLockDistance(16.000_001D));
        assertFalse(AimAssistClient.isWithinLockDistance(Double.NaN));
        assertFalse(AimAssistClient.isWithinLockDistance(Double.POSITIVE_INFINITY));
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
                "ordinary combat movement keeps the same target latched");
    }
}
