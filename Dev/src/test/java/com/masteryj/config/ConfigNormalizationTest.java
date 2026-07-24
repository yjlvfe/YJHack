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
    void autoLeftClampsCpsToThirtyAndFixesInversion() {
        AutoLeftClient.Config c = new AutoLeftClient.Config();
        c.minCps = 9999;   // absurd -> clamps to 30
        c.maxCps = -5;      // non-positive -> clamps to 1, then min>max is corrected by swap
        c.normalize();
        assertEquals(1, c.minCps, "after clamp+swap the lower bound is 1");
        assertEquals(30, c.maxCps, "after clamp+swap the upper bound is the 30 CPS ceiling");
        assertTrue(c.minCps <= c.maxCps, "min must never exceed max");
    }

    @Test
    void autoRightClampsCpsToThirty() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.minCps = 0;
        c.maxCps = 500_000;
        c.normalize();
        assertEquals(1, c.minCps);
        assertEquals(30, c.maxCps, "absurd CPS clamps down to the 30 CPS ceiling");
    }

    @Test
    void minGreaterThanMaxIsSwapped() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.minCps = 25;   // both in range but inverted
        c.maxCps = 10;
        c.normalize();
        assertEquals(10, c.minCps, "min>max is corrected (swapped), not left inverted");
        assertEquals(25, c.maxCps);
        assertTrue(c.minCps <= c.maxCps);
    }

    @Test
    void handEditedHundredAndThousandAreClamped() {
        AutoLeftClient.Config left = new AutoLeftClient.Config();
        left.minCps = 100;
        left.maxCps = 1000;
        left.normalize();
        assertEquals(30, left.minCps);
        assertEquals(30, left.maxCps);
    }

    @Test
    void autoRightDefaultsAreValidAndVersionBumps() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.configVersion = 1; // simulate an old file
        c.normalize();
        assertEquals(4, c.configVersion, "config version is upgraded, not reset");
        assertTrue(c.minCps >= 1 && c.minCps <= 30);
        assertTrue(c.maxCps >= 1 && c.maxCps <= 30);
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
        assertEquals(28, right.maxCps);
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
