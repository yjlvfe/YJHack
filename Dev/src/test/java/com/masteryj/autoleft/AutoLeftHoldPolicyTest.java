package com.masteryj.autoleft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoLeftHoldPolicyTest {

    @Test
    void heldAttackRunsOnlyOnARealEntityTarget() {
        assertTrue(AutoLeftClient.shouldRunHeldAttack(
                true, true, true, true, true));
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                true, true, true, true, false),
                "air misses must not trigger Minecraft's miss cooldown");
    }

    @Test
    void heldAttackStopsForEverySafetyGate() {
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                false, true, true, true, true), "disabled module must not emit");
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                true, false, true, true, true), "menus and focus loss must stop emission");
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                true, true, false, true, true), "released input must cancel pending work");
        assertFalse(AutoLeftClient.shouldRunHeldAttack(
                true, true, true, false, true), "weapon gate must remain respected");
    }
}
