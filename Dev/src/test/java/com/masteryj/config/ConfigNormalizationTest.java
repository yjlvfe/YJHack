package com.masteryj.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.masteryj.aimassist.AimAssistClient;
import com.masteryj.autoleft.AutoLeftClient;
import com.masteryj.autoright.AutoRightClient;
import com.masteryj.ninjabridge.NinjaBridgeClient;
import com.masteryj.tracker.TrackerClient;
import org.junit.jupiter.api.Test;

/**
 * Verifies the REAL {@code Config.normalize()} / {@code norm()} of every module — the
 * hand-edit / corrupt-file guardrails. Runs under fabric-loader-junit, which provides a
 * FabricLoader so the module classes (whose static config-path init calls
 * {@code FabricLoader.getInstance()}) can load. No copies: these are the production
 * config types the GUI edits and the modules serialize.
 */
class ConfigNormalizationTest {

    // ---- Min CPS / Max CPS validation (AutoLeft + AutoRight) ----

    @Test
    void autoLeftClampsCpsToFortyAndFixesInversion() {
        AutoLeftClient.Config c = new AutoLeftClient.Config();
        c.minCps = 9999;   // absurd -> clamps to 40
        c.maxCps = -5;      // non-positive -> clamps to 1, then min>max is corrected by swap
        c.normalize();
        assertEquals(1, c.minCps, "after clamp+swap the lower bound is 1");
        assertEquals(40, c.maxCps, "after clamp+swap the upper bound is the 40 CPS ceiling");
        assertTrue(c.minCps <= c.maxCps, "min must never exceed max");
    }

    @Test
    void autoRightClampsCpsToForty() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.minCps = 0;
        c.maxCps = 500_000;
        c.normalize();
        assertEquals(1, c.minCps);
        assertEquals(40, c.maxCps, "absurd CPS clamps down to the 40 CPS ceiling");
    }

    @Test
    void cpsBoundaryValuesClampAtForty() {
        // The exact contract the spec requires: 1 stays 1, 20/40 stay put, and every
        // unexecutable value (41 / 100 / 1000) is pulled down to the 40 ceiling.
        assertEquals(1, normalizedMax(1),  "1 CPS is preserved");
        assertEquals(20, normalizedMax(20), "20 CPS is preserved");
        assertEquals(40, normalizedMax(40), "40 CPS is preserved");
        assertEquals(40, normalizedMax(41), "41 -> 40");
        assertEquals(40, normalizedMax(100), "100 -> 40");
        assertEquals(40, normalizedMax(1000), "1000 -> 40");
    }

    @Test
    void negativeCpsBecomesSafeMinimum() {
        AutoLeftClient.Config c = new AutoLeftClient.Config();
        c.minCps = -100;
        c.maxCps = -1;
        c.normalize();
        assertEquals(1, c.minCps, "negative min -> safe minimum 1");
        assertEquals(1, c.maxCps, "negative max -> safe minimum 1");
        assertTrue(c.minCps <= c.maxCps);
    }

    /** Run the real AutoLeft normalize with min==max==cps and return the clamped value. */
    private static int normalizedMax(int cps) {
        AutoLeftClient.Config c = new AutoLeftClient.Config();
        c.minCps = cps;
        c.maxCps = cps;
        c.normalize();
        return c.maxCps;
    }

    @Test
    void minGreaterThanMaxIsSwapped() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.minCps = 18;   // both in range (<=40) but inverted
        c.maxCps = 7;
        c.normalize();
        assertEquals(7, c.minCps, "min>max is corrected (swapped), not left inverted");
        assertEquals(18, c.maxCps);
        assertTrue(c.minCps <= c.maxCps);
    }

    @Test
    void handEditedHundredAndThousandAreClamped() {
        AutoLeftClient.Config left = new AutoLeftClient.Config();
        left.minCps = 100;
        left.maxCps = 1000;
        left.normalize();
        assertEquals(40, left.minCps);
        assertEquals(40, left.maxCps);
    }

    @Test
    void autoRightDefaultsAreValidAndVersionBumps() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.configVersion = 1; // simulate an old file
        c.normalize();
        assertEquals(5, c.configVersion, "config version is upgraded, not reset");
        assertTrue(c.minCps >= 1 && c.minCps <= 40);
        assertTrue(c.maxCps >= 1 && c.maxCps <= 40);
    }

    @Test
    void defaultCpsValuesSurviveNormalize() {
        // The shipped v5 reset defaults must sit inside the ceiling untouched.
        AutoLeftClient.Config left = new AutoLeftClient.Config();
        left.normalize();
        assertEquals(8, left.minCps);
        assertEquals(10, left.maxCps);
        AutoRightClient.Config right = new AutoRightClient.Config();
        right.normalize();
        assertEquals(8, right.minCps);
        assertEquals(10, right.maxCps);
    }

    // ---- v4 -> v5 default migration (preserve customised values) ----

    @Test
    void autoLeftLegacyDefaultsMigrateToBalancedDefaults() {
        AutoLeftClient.Config c = new AutoLeftClient.Config();
        c.configVersion = 4;   // a pre-v5 file
        c.minCps = 8;          // exactly the shipped legacy defaults
        c.maxCps = 16;
        c.normalize();
        assertEquals(5, c.configVersion, "version is bumped");
        assertEquals(8, c.minCps, "legacy default min moves to the balanced reset value");
        assertEquals(10, c.maxCps, "legacy default max moves to the balanced reset value");
    }

    @Test
    void autoLeftCustomisedValuesSurviveMigration() {
        AutoLeftClient.Config c = new AutoLeftClient.Config();
        c.configVersion = 4;
        c.minCps = 10;         // hand-customised, NOT the legacy defaults
        c.maxCps = 18;
        c.normalize();
        assertEquals(5, c.configVersion, "version is bumped");
        assertEquals(10, c.minCps, "a customised min is preserved, never clobbered by the bump");
        assertEquals(18, c.maxCps, "a customised max is preserved");
    }

    @Test
    void autoRightLegacyDefaultsMigrateToBalancedDefaults() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.configVersion = 4;
        c.minCps = 14;         // the shipped legacy AutoRight defaults
        c.maxCps = 20;
        c.normalize();
        assertEquals(5, c.configVersion);
        assertEquals(8, c.minCps);
        assertEquals(10, c.maxCps);
    }

    @Test
    void autoRightCustomisedValuesSurviveMigration() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.configVersion = 4;
        c.minCps = 12;         // customised, NOT the legacy pair
        c.maxCps = 19;
        c.normalize();
        assertEquals(5, c.configVersion);
        assertEquals(12, c.minCps, "customised min preserved");
        assertEquals(19, c.maxCps, "customised max preserved");
    }

    // ---- AimAssist float sanitisation ----

    @Test
    void aimAssistSanitisesNaNAndClampsBounds() {
        AimAssistClient.Config c = new AimAssistClient.Config();
        c.speed = Float.NaN;      // corrupt -> fallback
        c.smoothness = 5.0f;      // above range -> clamp to 1.0
        c.fov = 1.0f;             // below range -> clamp to 10
        c.normalize();
        assertEquals(0.28f, c.speed, 1e-6, "NaN speed falls back to balanced default");
        assertEquals(1.0f, c.smoothness, 1e-6);
        assertEquals(10.0f, c.fov, 1e-6);
    }

    // ---- Tracker range/HUD clamping ----

    @Test
    void trackerClampsRangeAndSanitisesNaN() {
        TrackerClient.Config c = new TrackerClient.Config();
        c.range = Double.NaN;
        c.normalize();
        assertEquals(48.0, c.range, 1e-6, "NaN range falls back to balanced default");

        TrackerClient.Config c2 = new TrackerClient.Config();
        c2.range = 10_000.0;
        c2.normalize();
        assertEquals(256.0, c2.range, 1e-6, "range clamps to max 256");
    }

    // ---- NinjaBridge migration ----

    @Test
    void ninjaBridgeMigratesOldVersionWithoutEnablingAutoSwitch() {
        NinjaBridgeClient.Config c = new NinjaBridgeClient.Config();
        c.configVersion = 1; // old file -> migrate
        c.norm();
        assertEquals(8, c.configVersion);
        assertFalse(c.autoSwitch, "migration must not enable automatic slot switching");
    }
}
