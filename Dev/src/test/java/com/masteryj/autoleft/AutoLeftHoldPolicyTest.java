package com.masteryj.autoleft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoLeftHoldPolicyTest {

    @Test
    void heldAttackContinuesWithoutAnExactEntityHit() {
        assertTrue(AutoLeftClient.shouldRunHeldAttack(
                true, true, true, true, false),
                "air or a brief crosshair miss must not cancel a valid long hold");
    }

    @Test
    void heldAttackStopsOnlyForRealSafetyGates() {
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                false, true, true, true, false), "disabled module must not emit");
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                true, false, true, true, false), "menus and focus loss must stop emission");
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                true, true, false, true, false), "released input must cancel pending work");
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                true, true, true, false, false), "weapon gate must remain respected");
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                true, true, true, true, true), "block mining must remain vanilla");
    }
}
