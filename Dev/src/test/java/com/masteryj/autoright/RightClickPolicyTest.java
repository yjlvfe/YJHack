package com.masteryj.autoright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Exercises the REAL {@link RightClickPolicy} classification contract (not a copy).
 *
 * <p>The registry-path rule is the single source of truth for "which items must fire
 * exactly one use per press". {@link RightClickPolicy#isSinglePressPath(String)} is the
 * pure core of that rule, so it can be verified without bootstrapping Minecraft while
 * still testing production code directly.
 */
class RightClickPolicyTest {

    @Test
    void kindHasThreeCategories() {
        assertEquals(3, RightClickPolicy.Kind.values().length);
    }

    @Test
    void fireChargeIsSinglePress() {
        // The mandatory case: fireball / Fire Charge must never CPS-repeat.
        assertTrue(RightClickPolicy.isSinglePressPath("fire_charge"));
    }

    @Test
    void discreteThrowablesAreSinglePress() {
        for (String path : new String[] {
                "ender_pearl", "snowball", "egg", "splash_potion", "lingering_potion",
                "experience_bottle", "ender_eye", "wind_charge", "fishing_rod",
                "trident", "bow", "crossbow" }) {
            assertTrue(RightClickPolicy.isSinglePressPath(path), path + " should be single-press");
        }
    }

    @Test
    void everyBucketIsSinglePress() {
        assertTrue(RightClickPolicy.isSinglePressPath("bucket"));
        assertTrue(RightClickPolicy.isSinglePressPath("water_bucket"));
        assertTrue(RightClickPolicy.isSinglePressPath("lava_bucket"));
        assertTrue(RightClickPolicy.isSinglePressPath("powder_snow_bucket"));
        assertTrue(RightClickPolicy.isSinglePressPath("axolotl_bucket"));
    }

    @Test
    void blocksAndOrdinaryItemsAreNotSinglePress() {
        // Blocks must stay eligible for Block-Mode CPS (i.e. NOT forced single-press),
        // and everyday items must pass through untouched.
        for (String path : new String[] {
                "stone", "dirt", "cobblestone", "oak_planks", "sandstone", // blocks
                "diamond_sword", "apple", "bread", "shield", "spyglass" }) { // pass-through
            assertFalse(RightClickPolicy.isSinglePressPath(path), path + " should NOT be single-press");
        }
    }

    @Test
    void nullPathIsSafe() {
        assertFalse(RightClickPolicy.isSinglePressPath(null));
    }

    @Test
    void nullStackClassifiesAsPassThrough() {
        // classify() short-circuits on a null stack before touching any registry,
        // so this exercises the real method with no Minecraft runtime needed.
        assertEquals(RightClickPolicy.Kind.PASS_THROUGH, RightClickPolicy.classify(null, null));
    }

    @Test
    void shouldAutoRepeatIsFalseWithoutABlockInBlockMode() {
        // No stack -> never auto-repeat, regardless of Block Mode.
        assertFalse(RightClickPolicy.shouldAutoRepeat(null, true, null));
        assertFalse(RightClickPolicy.shouldAutoRepeat(null, false, null));
    }
}
