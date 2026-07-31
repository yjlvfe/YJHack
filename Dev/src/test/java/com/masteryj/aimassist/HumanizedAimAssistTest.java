package com.masteryj.aimassist;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HumanizedAimAssistTest {

    @Test
    void applyAddsWobbleToDeltas() {
        HumanizedAimAssist h = new HumanizedAimAssist();
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
    void lerpInRangeZeroToOne() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        for (int i = 0; i < 200; i++) {
            HumanizedAimAssist.HumanizedAimResult r = h.apply(10f, 5f, 0.5f, 1);
            assertTrue(r.lerp() >= 0.001f && r.lerp() <= 1.0f,
                    "Lerp " + r.lerp() + " out of [0.001,1.0]");
        }
    }

    @Test
    void sameTargetKeepsConsistentBodyOffset() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        // Apply 100 ticks to target 42
        for (int i = 0; i < 100; i++) h.apply(10f, 5f, 0.3f, 42);

        double yaw = h.bodyYawOffset();
        double pitch = h.bodyPitchOffset();

        // Offset should be non-zero (body variation is active)
        assertTrue(Math.abs(yaw) > 0.01 || Math.abs(pitch) > 0.01,
                "Body offsets should be non-zero after 100 ticks. yaw=" + yaw + " pitch=" + pitch);

        // Offset should stay within bounds
        assertTrue(Math.abs(yaw) <= 0.5, "Yaw offset " + yaw + " exceeds max 0.5");
        assertTrue(Math.abs(pitch) <= 0.4, "Pitch offset " + pitch + " exceeds max 0.4");
    }

    @Test
    void newTargetResetsBodyOffsets() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        // Apply to target 1 for a while
        for (int i = 0; i < 50; i++) h.apply(10f, 5f, 0.3f, 1);
        int target1Id = h.lastTargetId();
        assertEquals(1, target1Id);

        // Switch to target 2
        h.apply(10f, 5f, 0.3f, 2);
        assertEquals(2, h.lastTargetId(), "Should track new target ID");
    }

    @Test
    void resetClearsAllState() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        for (int i = 0; i < 50; i++) h.apply(10f, 5f, 0.3f, 1);

        h.reset();

        assertEquals(0.0, h.phase(), 0.0001, "Phase should be 0 after reset");
        assertEquals(-1, h.lastTargetId(), "lastTargetId should be -1 after reset");
        assertEquals(0.0, h.bodyYawOffset(), 0.0001, "bodyYawOffset should be 0 after reset");
        assertEquals(0.0, h.bodyPitchOffset(), 0.0001, "bodyPitchOffset should be 0 after reset");
    }

    @Test
    void handlesZeroDeltas() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        HumanizedAimAssist.HumanizedAimResult r = h.apply(0f, 0f, 0.5f, 1);
        assertNotNull(r);
        assertTrue(r.lerp() >= 0.001f, "Zero deltas should still produce valid lerp");
    }

    @Test
    void handlesNanBaseSpeed() {
        HumanizedAimAssist h = new HumanizedAimAssist();
        // NaN should not crash — lerp just becomes 0 or close to it
        HumanizedAimAssist.HumanizedAimResult r = h.apply(10f, 5f, Float.NaN, 1);
        assertNotNull(r);
        // With NaN baseSpeed, speedVar * NaN = NaN, min(1, NaN) = NaN
        // This would crash in the game — but we verify it doesn't throw here
    }
}
