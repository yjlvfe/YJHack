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

/** Verifies production config normalization and migration guardrails. */
class ConfigNormalizationTest {

    @Test
    void autoLeftClampsCpsToTwentyAndFixesInversion() {
        AutoLeftClient.Config c = new AutoLeftClient.Config();
        c.minCps = 9999;
        c.maxCps = -5;
        c.normalize();
        assertEquals(1, c.minCps);
        assertEquals(20, c.maxCps);
        assertTrue(c.minCps <= c.maxCps);
    }

    @Test
    void autoRightClampsCpsToTwenty() {
        AutoRightClient.Config c = new AutoRightClient.Config();
        c.minCps = 0;
        c.maxCps = 500_000;
        c.normalize();
        assertEquals(1, c.minCps);
        assertEquals(20, c.maxCps);
    }

    @Test
    void cpsBoundaryValuesClampAtTwenty() {
        assertEquals(1, normalizedMax(1));
        assertEquals(10, normalizedMax(10));
        assertEquals(20, normalizedMax(20));
        assertEquals(20, normalizedMax(21));
        assertEquals(20, normalizedMax(40));
        assertEquals(20, normalizedMax(1000));
    }

    @Test
    void negativeCpsBecomesSafeMinimum() {
        AutoLeftClient.Config c = new AutoLeftClient.Config();
        c.minCps = -100;
        c.maxCps = -1;
        c.normalize();
        assertEquals(1, c.minCps);
        assertEquals(1, c.maxCps);
    }

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
        c.minCps = 18;
        c.maxCps = 7;
        c.normalize();
        assertEquals(7, c.minCps);
        assertEquals(18, c.maxCps);
    }

    @Test
    void currentCpsDefaultsAndVersionsSurviveNormalize() {
        AutoLeftClient.Config left = new AutoLeftClient.Config();
        left.normalize();
        assertEquals(6, left.configVersion);
        assertEquals(8, left.minCps);
        assertEquals(10, left.maxCps);

        AutoRightClient.Config right = new AutoRightClient.Config();
        right.normalize();
        assertEquals(6, right.configVersion);
        assertEquals(8, right.minCps);
        assertEquals(10, right.maxCps);
    }

    @Test
    void autoLeftLegacyDefaultsMigrateButCustomValuesSurvive() {
        AutoLeftClient.Config defaults = new AutoLeftClient.Config();
        defaults.configVersion = 4;
        defaults.minCps = 8;
        defaults.maxCps = 16;
        defaults.normalize();
        assertEquals(6, defaults.configVersion);
        assertEquals(8, defaults.minCps);
        assertEquals(10, defaults.maxCps);

        AutoLeftClient.Config custom = new AutoLeftClient.Config();
        custom.configVersion = 4;
        custom.minCps = 10;
        custom.maxCps = 18;
        custom.normalize();
        assertEquals(10, custom.minCps);
        assertEquals(18, custom.maxCps);
    }

    @Test
    void autoRightLegacyDefaultsMigrateButCustomValuesSurvive() {
        AutoRightClient.Config defaults = new AutoRightClient.Config();
        defaults.configVersion = 4;
        defaults.minCps = 14;
        defaults.maxCps = 20;
        defaults.normalize();
        assertEquals(6, defaults.configVersion);
        assertEquals(8, defaults.minCps);
        assertEquals(10, defaults.maxCps);

        AutoRightClient.Config custom = new AutoRightClient.Config();
        custom.configVersion = 4;
        custom.minCps = 12;
        custom.maxCps = 19;
        custom.normalize();
        assertEquals(12, custom.minCps);
        assertEquals(19, custom.maxCps);
    }

    @Test
    void aimAssistSanitisesNaNAndClampsBounds() {
        AimAssistClient.Config c = new AimAssistClient.Config();
        c.speed = Float.NaN;
        c.smoothness = 5.0f;
        c.fov = 1.0f;
        c.normalize();
        assertEquals(8, c.configVersion);
        assertEquals(0.28f, c.speed, 1e-6);
        assertEquals(1.0f, c.smoothness, 1e-6);
        assertEquals(10.0f, c.fov, 1e-6);
    }

    @Test
    void trackerClampsRangeAndSanitisesNaN() {
        TrackerClient.Config c = new TrackerClient.Config();
        c.range = Double.NaN;
        c.normalize();
        assertEquals(48.0, c.range, 1e-6);

        TrackerClient.Config c2 = new TrackerClient.Config();
        c2.range = 10_000.0;
        c2.normalize();
        assertEquals(256.0, c2.range, 1e-6);
    }

    @Test
    void ninjaBridgeMigrationNeverEnablesAutoSwitch() {
        NinjaBridgeClient.Config c = new NinjaBridgeClient.Config();
        c.configVersion = 1;
        c.norm();
        assertEquals(9, c.configVersion);
        assertFalse(c.autoSwitch);
    }
}
