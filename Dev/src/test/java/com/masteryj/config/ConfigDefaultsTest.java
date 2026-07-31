package com.masteryj.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.masteryj.aimassist.AimAssistClient;
import com.masteryj.autoleft.AutoLeftClient;
import com.masteryj.autoright.AutoRightClient;
import com.masteryj.ninjabridge.NinjaBridgeClient;
import com.masteryj.tracker.TrackerClient;
import org.junit.jupiter.api.Test;

class ConfigDefaultsTest {

    @Test
    void recommendedProfilesAreDisabledLegalAndBalanced() {
        AutoLeftClient.Config left = RecommendedProfiles.autoLeft();
        assertFalse(left.enabled);
        assertEquals(12, left.cps);

        AutoRightClient.Config right = RecommendedProfiles.autoRight();
        assertFalse(right.enabled);
        assertEquals(10, right.cps);

        NinjaBridgeClient.Config bridge = RecommendedProfiles.ninjaBridge();
        assertFalse(bridge.enabled);
        assertTrue(bridge.autoSwitch);
        assertEquals(120, bridge.switchDelayMs);

        AimAssistClient.Config aim = RecommendedProfiles.aimAssist();
        assertFalse(aim.enabled);
        assertEquals(0.22F, aim.speed, 0.0001F);
        assertEquals(0.62F, aim.smoothness, 0.0001F);
        assertEquals(70.0F, aim.fov, 0.0001F);
        assertEquals(3.5D, aim.range, 0.0001D);
        assertTrue(aim.stickyLock);
        assertTrue(aim.lineOfSight);
        assertTrue(aim.bedLock);

        TrackerClient.Config tracker = RecommendedProfiles.tracker();
        assertFalse(tracker.enabled);
        assertTrue(tracker.ignoreOwnTeam);
        assertEquals(48.0D, tracker.range, 0.0001D);
        assertEquals(8, tracker.hudOffsetX);
        assertEquals(8, tracker.hudY);
    }

    @Test
    void everyRecommendedProfileFactoryReturnsAFreshObject() {
        AutoLeftClient.Config leftA = RecommendedProfiles.autoLeft();
        AutoLeftClient.Config leftB = RecommendedProfiles.autoLeft();
        assertNotSame(leftA, leftB);
        leftA.cps = 40;
        assertEquals(12, leftB.cps);

        AimAssistClient.Config aimA = RecommendedProfiles.aimAssist();
        AimAssistClient.Config aimB = RecommendedProfiles.aimAssist();
        assertNotSame(aimA, aimB);
        aimA.range = 1.0D;
        assertEquals(3.5D, aimB.range, 0.0D);
    }
}
