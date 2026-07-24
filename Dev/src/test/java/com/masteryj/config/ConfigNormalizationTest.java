package com.masteryj.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void autoLeftClampsCpsToTwentyAndFixesInversion() {
        AutoLeftClient.Config c = new AutoLeftClient.Config();
        c.minCps = 9999;   // absurd -> clamps to 20
        c.maxCps = -5;      // non-positive -> clamps to 1, then min>max is corrected by swap
        c.normalize();
        assertEquals(1, c.minCps, "after clamp+swap the lower bound is 1");
        assertEquals(20, c.maxCps, "after clamp+swap the upper bound is the 20 CPS ceiling");
        assertTrue(c.minCps <= c.maxCps, "min must never exceed max");
    }

    @Test
    void autoRightClampsCpsToTwenty() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.minCps = 0;
        c.maxCps = 500_000;
        c.normalize();
        assertEquals(1, c.minCps);
        assertEquals(20, c.maxCps, "absurd CPS clamps down to the 20 CPS ceiling");
    }

    @Test
    void cpsBoundaryValuesClampAtTwenty() {
        // The exact contract the spec requires: 1 stays 1, 20 stays 20, and every
        // unexecutable value (21 / 30 / 40 / 1000) is pulled down to the 20 ceiling.
        assertEquals(1, normalizedMax(1),  "1 CPS is preserved");
        assertEquals(20, normalizedMax(20), "20 CPS is preserved");
        assertEquals(20, normalizedMax(21), "21 -> 20");
        assertEquals(20, normalizedMax(30), "30 -> 20");
        assertEquals(20, normalizedMax(40), "40 -> 20");
        assertEquals(20, normalizedMax(1000), "1000 -> 20");
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
        c.minCps = 18;   // both in range (<=20) but inverted
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
        assertEquals(20, left.minCps);
        assertEquals(20, left.maxCps);
    }

    @Test
    void autoRightDefaultsAreValidAndVersionBumps() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.configVersion = 1; // simulate an old file
        c.normalize();
        assertEquals(4, c.configVersion, "config version is upgraded, not reset");
        assertTrue(c.minCps >= 1 && c.minCps <= 20);
        assertTrue(c.maxCps >= 1 && c.maxCps <= 20);
    }

    @Test
    void defaultCpsValuesSurviveNormalize() {
        // The shipped defaults must sit inside the ceiling untouched (no behaviour change).
        AutoLeftClient.Config left = new AutoLeftClient.Config();
        left.normalize();
        assertEquals(8, left.minCps);
        assertEquals(16, left.maxCps);
        AutoRightClient.Config right = new AutoRightClient.Config();
        right.normalize();
        assertEquals(14, right.minCps);
        assertEquals(20, right.maxCps, "AutoRight default max now sits on the 20 ceiling");
    }

    // ---- AimAssist float sanitisation ----

    @Test
    void aimAssistSanitisesNaNAndClampsBounds() {
        AimAssistClient.Config c = new AimAssistClient.Config();
        c.speed = Float.NaN;      // corrupt -> fallback
        c.smoothness = 5.0f;      // above range -> clamp to 1.0
        c.fov = 1.0f;             // below range -> clamp to 10
        c.normalize();
        assertEquals(0.24f, c.speed, 1e-6, "NaN speed falls back to default");
        assertEquals(1.0f, c.smoothness, 1e-6);
        assertEquals(10.0f, c.fov, 1e-6);
    }

    // ---- Tracker range/HUD clamping ----

    @Test
    void trackerClampsRangeAndSanitisesNaN() {
        TrackerClient.Config c = new TrackerClient.Config();
        c.range = Double.NaN;
        c.normalize();
        assertEquals(96.0, c.range, 1e-6, "NaN range falls back to default");

        TrackerClient.Config c2 = new TrackerClient.Config();
        c2.range = 10_000.0;
        c2.normalize();
        assertEquals(256.0, c2.range, 1e-6, "range clamps to max 256");
    }

    // ---- NinjaBridge migration ----

    @Test
    void ninjaBridgeMigratesOldVersionAndKeepsAutoSwitch() {
        NinjaBridgeClient.Config c = new NinjaBridgeClient.Config();
        c.configVersion = 1; // old file -> migrate
        c.norm();
        assertEquals(7, c.configVersion);
        assertTrue(c.autoSwitch, "migration enables auto-switch");
    }
}
