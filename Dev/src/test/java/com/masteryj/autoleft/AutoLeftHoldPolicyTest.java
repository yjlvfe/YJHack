package com.masteryj.autoleft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoLeftHoldPolicyTest {

    @Test
    void directAttackRequiresARealEntityTarget() {
        assertTrue(AutoLeftClient.shouldRunDirectAttack(true, true, true, true));
        assertFalse(AutoLeftClient.shouldRunDirectAttack(true, true, true, false),
                "air misses must not trigger direct attacks or miss cooldown");
    }

    @Test
    void everySafetyGateStopsDirectAttack() {
        assertFalse(AutoLeftClient.shouldRunDirectAttack(false, true, true, true));
        assertFalse(AutoLeftClient.shouldRunDirectAttack(true, false, true, true));
        assertFalse(AutoLeftClient.shouldRunDirectAttack(true, true, false, true));
    }
}
