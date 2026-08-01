package com.masteryj.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.masteryj.aimassist.AimAssistClient;
import com.masteryj.autoleft.AutoLeftClient;
import com.masteryj.autoright.AutoRightClient;
import com.masteryj.ninjabridge.NinjaBridgeClient;
import com.masteryj.tracker.TrackerClient;
import org.junit.jupiter.api.Test;

class ConfigNormalizationTest {

    @Test
    void leftAndRightUseOneIndependentCpsClampedToForty() {
        AutoLeftClient.Config left = new AutoLeftClient.Config();
        left.cps = 999;
        left.normalize();
        assertEquals(9, left.configVersion);
        assertEquals(40, left.cps);

        AutoRightClient.Config right = new AutoRightClient.Config();
        right.cps = -5;
        right.normalize();
        assertEquals(10, right.configVersion);
        assertEquals(1, right.cps);
    }

    @Test
    void oldMinMaxFilesMigrateToOneFixedCps() {
        AutoLeftClient.Config left = new AutoLeftClient.Config();
        left.configVersion = 6;
        left.minCps = 8;
        left.maxCps = 18;
        left.normalize();
        assertEquals(18, left.cps);
        assertNull(left.minCps);
        assertNull(left.maxCps);

        AutoRightClient.Config right = new AutoRightClient.Config();
        right.configVersion = 6;
        right.minCps = 7;
        right.maxCps = 20;
        right.normalize();
        assertEquals(20, right.cps);
        assertNull(right.minCps);
        assertNull(right.maxCps);
    }

    @Test
    void exactVersionEightDefaultMigratesToResponsiveFixedTwenty() {
        AutoRightClient.Config oldDefault = new AutoRightClient.Config();
        oldDefault.configVersion = 8;
        oldDefault.cps = 10;
        oldDefault.normalize();
        assertEquals(10, oldDefault.configVersion);
        assertEquals(20, oldDefault.cps);

        AutoRightClient.Config custom = new AutoRightClient.Config();
        custom.configVersion = 8;
        custom.cps = 16;
        custom.normalize();
        assertEquals(16, custom.cps);
    }

    @Test
    void aimAssistSanitisesFloatsAndHardClampsRangeAndLos() {
        AimAssistClient.Config cfg = new AimAssistClient.Config();
        cfg.speed = Float.NaN;
        cfg.smoothness = 5.0F;
        cfg.fov = 1.0F;
        cfg.range = 999.0D;
        cfg.lineOfSight = false;
        cfg.normalize();
        assertEquals(10, cfg.configVersion);
        assertEquals(0.22F, cfg.speed, 1.0E-6F);
        assertEquals(1.0F, cfg.smoothness, 1.0E-6F);
        assertEquals(10.0F, cfg.fov, 1.0E-6F);
        assertEquals(6.0D, cfg.range, 1.0E-6D);
        assertTrue(cfg.lineOfSight);
    }

    @Test
    void trackerStillSanitisesRange() {
        TrackerClient.Config cfg = new TrackerClient.Config();
        cfg.range = Double.NaN;
        cfg.normalize();
        assertEquals(48.0D, cfg.range, 1.0E-6D);
    }

    @Test
    void ninjaRecommendedMigrationUsesConservativeSlotDelay() {
        NinjaBridgeClient.Config cfg = new NinjaBridgeClient.Config();
        cfg.configVersion = 1;
        cfg.switchDelayMs = -50;
        cfg.norm();
        assertEquals(10, cfg.configVersion);
        assertTrue(cfg.autoSwitch);
        assertEquals(50, cfg.switchDelayMs);
    }
}
