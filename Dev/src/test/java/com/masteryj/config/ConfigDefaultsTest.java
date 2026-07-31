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

class ConfigDefaultsTest {

    @Test
    void resetDefaultsAreSimpleDisabledAndBalanced() {
        AutoLeftClient.Config left = new AutoLeftClient.Config();
        assertFalse(left.enabled);
        assertEquals(10, left.cps);

        AutoRightClient.Config right = new AutoRightClient.Config();
        assertFalse(right.enabled);
        assertEquals(10, right.cps);

        NinjaBridgeClient.Config bridge = new NinjaBridgeClient.Config();
        assertFalse(bridge.enabled);
        assertFalse(bridge.autoSwitch);

        AimAssistClient.Config aim = new AimAssistClient.Config();
        assertFalse(aim.enabled);
        assertEquals(0.28F, aim.speed, 0.0001F);
        assertEquals(0.45F, aim.smoothness, 0.0001F);
        assertEquals(70.0F, aim.fov, 0.0001F);

        TrackerClient.Config tracker = new TrackerClient.Config();
        assertFalse(tracker.enabled);
        assertTrue(tracker.ignoreOwnTeam);
        assertEquals(48.0D, tracker.range, 0.0001D);
        assertEquals(8, tracker.hudOffsetX);
        assertEquals(8, tracker.hudY);
    }
}
