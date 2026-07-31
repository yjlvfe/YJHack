package com.masteryj.aimassist;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Humanizes AimAssist movement — no missed shots, just human-like imperfection.
 *
 * <p>Four independent dimensions of humanization:
 * <ol>
 *   <li>Micro-wobble: tiny random offset every tick</li>
 *   <li>Inconsistent speed: lerp varies per tick, scaled by CPS</li>
 *   <li>Organic path: gentle sine-wave deviation while tracking</li>
 *   <li>Body variation: per-target aim point drift (stable per entity)</li>
 * </ol>
 *
 * <p>Uses targetId to seed per-target offsets so the same target always gets
 * the same body drift — looks like a human aiming at a specific body part.
 */
public final class HumanizedAimAssist {

    private static final RandomGenerator RNG =
            RandomGeneratorFactory.getDefault().create();

    private double phase;
    private long lastBodySwitchNanos;
    private int lastTargetId = -1;
    private double bodyYawOffset;
    private double bodyPitchOffset;

    /**
     * Apply humanized corrections to aim movement.
     *
     * @param yawDelta      raw yaw delta from aim calculation
     * @param pitchDelta    raw pitch delta from aim calculation
     * @param baseSpeed     raw speed factor (0.005–1.0)
     * @param targetId      entity ID for per-target body variation
     * @return corrected deltas and lerp
     */
    public HumanizedAimResult apply(
            float yawDelta, float pitchDelta,
            float baseSpeed, int targetId) {

        // 1. Inconsistent speed: vary ±15% per tick (scaled by baseSpeed)
        float speedVar = 0.85F + RNG.nextFloat() * 0.30F;
        float lerp = Math.min(1.0F, baseSpeed * speedVar);

        // 2. Micro-wobble
        float wobbleYaw = (RNG.nextFloat() - 0.5F) * 0.6F;   // ±0.3°
        float wobblePitch = (RNG.nextFloat() - 0.5F) * 0.4F; // ±0.2°

        // 3. Organic path: gentle sine drift
        phase += 0.15 + RNG.nextDouble() * 0.05;
        double organicDrift = Math.sin(phase) * 0.4;

        // 4. Body variation — tied to targetId so it's stable per target
        updateBodyOffsets(targetId);

        // Compose final deltas
        // Use speed (not lerp) as the movement multiplier; humanizer adds wobble on top
        float finalYawDelta = yawDelta + wobbleYaw + (float) organicDrift
                + (float) bodyYawOffset;
        float finalPitchDelta = pitchDelta + wobblePitch + (float) bodyPitchOffset;

        return new HumanizedAimResult(finalYawDelta, finalPitchDelta, lerp);
    }

    private void updateBodyOffsets(int targetId) {
        if (targetId != lastTargetId) {
            // New target — generate fresh body aim point
            lastTargetId = targetId;
            bodyYawOffset = (RNG.nextDouble() - 0.5) * 0.5;   // ±0.25°
            bodyPitchOffset = (RNG.nextDouble() - 0.5) * 0.4; // ±0.20°
            lastBodySwitchNanos = System.nanoTime();
        }

        // Periodic drift within the same target (200-400ms)
        long now = System.nanoTime();
        if (now - lastBodySwitchNanos > bodySwitchInterval()) {
            lastBodySwitchNanos = now;
            // Small drift, not full reset — same body part, slight wander
            bodyYawOffset += (RNG.nextDouble() - 0.5) * 0.2;
            bodyPitchOffset += (RNG.nextDouble() - 0.5) * 0.15;
            // Clamp to reasonable limits
            bodyYawOffset = Math.max(-0.5, Math.min(0.5, bodyYawOffset));
            bodyPitchOffset = Math.max(-0.4, Math.min(0.4, bodyPitchOffset));
        }
    }

    private long bodySwitchInterval() {
        return 200_000_000L + RNG.nextLong(200_000_001L); // 200–400ms
    }

    /** Reset all state — call on world change, disable, or death. */
    public void reset() {
        phase = 0;
        lastBodySwitchNanos = 0;
        lastTargetId = -1;
        bodyYawOffset = 0;
        bodyPitchOffset = 0;
    }

    public record HumanizedAimResult(float yawDelta, float pitchDelta, float lerp) {}
}
