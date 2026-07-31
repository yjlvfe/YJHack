package com.masteryj.modgui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuiResetContractTest {

    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/com/masteryj/modgui/ModGuiClient.java"));
    }

    @Test
    void everyModulePageUsesFreshRecommendedProfiles() throws Exception {
        String source = source();
        assertTrue(source.contains("RecommendedProfiles.autoLeft()"));
        assertTrue(source.contains("RecommendedProfiles.autoRight()"));
        assertTrue(source.contains("RecommendedProfiles.ninjaBridge()"));
        assertTrue(source.contains("RecommendedProfiles.aimAssist()"));
        assertTrue(source.contains("RecommendedProfiles.tracker()"));
    }

    @Test
    void resetAppliesSavesRefreshesAndToastsWithoutClosing() throws Exception {
        String source = source();
        assertTrue(source.contains("replaceConfig.run();"));
        assertTrue(source.contains("saveNow();"));
        assertTrue(source.contains("refreshControls();"));
        assertTrue(source.contains("Recommended settings restored"));
        assertFalse(source.contains("client.setScreen(new AutoLeftScreen(parent))"));
        assertFalse(source.contains("client.setScreen(new AutoRightScreen(parent))"));
        assertFalse(source.contains("client.setScreen(new NinjaBridgeScreen(parent))"));
        assertFalse(source.contains("client.setScreen(new AimAssistScreen(parent))"));
        assertFalse(source.contains("client.setScreen(new TrackerScreen(parent))"));
    }

    @Test
    void guiHasNoManualSaveApplyConfirmOrGlobalReset() throws Exception {
        String source = source();
        assertFalse(source.contains("Text.literal(\"Save\")"));
        assertFalse(source.contains("Text.literal(\"Apply\")"));
        assertFalse(source.contains("Text.literal(\"Confirm\")"));
        assertFalse(source.contains("Global Reset"));
        assertTrue(source.contains("Text.literal(\"Reset\")"));
    }

    @Test
    void resetPathHasNoNetworkOrSyntheticInputDependency() throws Exception {
        String source = source();
        int start = source.indexOf("protected final void restoreRecommended");
        int end = source.indexOf("@Override", start);
        String resetMethod = source.substring(start, end);
        assertFalse(resetMethod.contains("Packet"));
        assertFalse(resetMethod.contains("attackKey"));
        assertFalse(resetMethod.contains("useKey"));
        assertFalse(resetMethod.contains("sneakKey"));
        assertFalse(resetMethod.contains("doAttack"));
        assertFalse(resetMethod.contains("doItemUse"));
        assertFalse(resetMethod.contains("close()"));
    }
}
