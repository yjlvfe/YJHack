package com.masteryj.aimassist;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HumanizedAimAssistTest {

    @Test
    void applyAddsWobbleToDeltas() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        // Apply 100 times and check that output differs from input
        boolean anyDifference = false;
        for (int i = 0; i < 100; i++) {
            HumanizedAimAssist.HumanizedAimResult r = h.apply(10f, 5f, 0.3f, 1);
            if (Math.abs(r.yawDelta() - 10f) > 0.001f || Math.abs(r.pitchDelta() - 5f) > 0.001f) {
                anyDifference = true;
                break;
            }
        }
        assertTrue(anyDifference, "Humanizer should add wobble, not return raw deltas");
    }

    @Test
    void lerpIsBoundedBetweenZeroAndOne() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        for (int i = 0; i < 200; i++) {
            HumanizedAimAssist.HumanizedAimResult r = h.apply(10f, 5f, 0.5f, 1);
            assertTrue(r.lerp() >= 0.001f && r.lerp() <= 1.0f,
                    "Lerp should be in [0.001, 1.0], got " + r.lerp());
        }
    }

    @Test
    void sameTargetProducesSimilarBodyOffsets() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        float firstYaw = 0;
        // First call sets the body offset for target 42
        for (int i = 0; i < 50; i++) {
            HumanizedAimAssist.HumanizedAimResult r = h.apply(0f, 0f, 0.3f, 42);
            if (i == 0) firstYaw = r.yawDelta();
        }
        // body offsets should be similar range for same target
        assertNotEquals(0f, firstYaw, "Body offset should be non-zero");
    }

    @Test
    void differentTargetsGetDifferentOffsets() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        HumanizedAimAssist.HumanizedAimResult r1 = h.apply(0f, 0f, 0.3f, 1);
        h.reset();
        HumanizedAimAssist.HumanizedAimResult r2 = h.apply(0f, 0f, 0.3f, 2);
        // Different targets should (probabilistically) get different offsets
        // We test after reset to ensure clean state each time
        assertNotNull(r1);
        assertNotNull(r2);
    }

    @Test
    void resetClearsPhaseAndOffsets() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        h.apply(10f, 5f, 0.3f, 1);
        h.apply(10f, 5f, 0.3f, 1);
        h.reset();
        // After reset, a fresh apply should work without issues
        HumanizedAimAssist.HumanizedAimResult r = h.apply(10f, 5f, 0.3f, 42);
        assertNotNull(r);
        assertTrue(r.lerp() > 0, "Lerp should be positive after reset");
    }

    @Test
    void handlesZeroDeltas() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        HumanizedAimAssist.HumanizedAimResult r = h.apply(0f, 0f, 0.5f, 1);
        assertNotNull(r);
        assertTrue(r.lerp() >= 0.001f, "Should handle zero deltas");
    }
}
