package com.masteryj.ninjabridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NinjaBridgeMigrationTest {

    @Test
    void explicitDisabledAutoSwitchSurvivesMigration() {
        NinjaBridgeClient.Config config = new NinjaBridgeClient.Config();
        config.configVersion = 6;
        config.autoSwitch = false;
        config.normalize(true);
        assertFalse(config.autoSwitch);
    }

    @Test
    void missingLegacyFieldReceivesSafeDefault() {
        NinjaBridgeClient.Config config = new NinjaBridgeClient.Config();
        config.configVersion = 5;
        config.autoSwitch = false; // Gson's absent primitive-field value
        config.normalize(false);
        assertTrue(config.autoSwitch);
    }
}
