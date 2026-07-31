package com.masteryj.ninjabridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NinjaBridgeMigrationTest {

    @Test
    void explicitDisabledAutoSwitchAndDelaySurviveMigration() {
        NinjaBridgeClient.Config config = new NinjaBridgeClient.Config();
        config.configVersion = 6;
        config.autoSwitch = false;
        config.switchDelayMs = 180;
        config.normalize(true, true);
        assertFalse(config.autoSwitch);
        assertEquals(180, config.switchDelayMs);
    }

    @Test
    void missingLegacyFieldsUseRecommendedValues() {
        NinjaBridgeClient.Config config = new NinjaBridgeClient.Config();
        config.configVersion = 5;
        config.autoSwitch = false;
        config.switchDelayMs = 0;
        config.normalize(false, false);
        assertTrue(config.autoSwitch);
        assertEquals(120, config.switchDelayMs);
    }
}
