package com.masteryj.aimassist;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Humanizes AimAssist movement — no missed shots, just human-like imperfection.
 *
 * <p>Four independent dimensions of humanization:
 * <ol>
 *   <li>Micro-wobble: tiny random offset every tick (±0.15°–0.5°)</li>
 *   <li>Inconsistent speed: lerp varies ±15% per tick</li>
 *   <li>Organic path: gentle sine-wave deviation while tracking</li>
 *   <li>Body variation: aim point drifts across the target's hitbox</li>
 * </ol>
 *
 * <p>All effects are subtle — a spectator won't see them, but pattern-based
 * anti-cheat cannot match the movement to any known automation signature.
 */
public final class HumanizedAimAssist {

    private static final RandomGenerator RNG =
            RandomGeneratorFactory.getDefault().create();

    // Per-instance state for organic continuity
    private double phase;
    private long lastBodySwitchNanos;
    private double bodyYawOffset;
    private double bodyPitchOffset;

    /**
     * Apply humanized corrections to raw aim angles.
     *
     * @param rawYawDelta   yaw delta from raw aim calculation
     * @param rawPitchDelta pitch delta from raw aim calculation
     * @param baseLerp      the base interpolation factor (speed × curve)
     * @param targetId      entity ID for per-target body variation stability
     * @return corrected lerp factor (already includes wobble + speed variance)
     */
    public HumanizedAimResult apply(
            float rawYawDelta, float rawPitchDelta,
            float baseLerp, int targetId) {

        // 1. Inconsistent speed: vary lerp ±15%
        float speedVariance = 0.85F + RNG.nextFloat() * 0.30F;
        float lerp = baseLerp * speedVariance;

        // 2. Micro-wobble: tiny jitter added every tick
        float wobbleYaw = (RNG.nextFloat() - 0.5F) * 0.6F;   // ±0.3°
        float wobblePitch = (RNG.nextFloat() - 0.5F) * 0.4F; // ±0.2°

        // 3. Organic path: gentle sine drift over time
        phase += 0.15 + RNG.nextDouble() * 0.05;
        double organicDrift = Math.sin(phase) * 0.4;

        // 4. Body variation: switch aim point every 200-400ms
        long now = System.nanoTime();
        if (lastBodySwitchNanos == 0 || now - lastBodySwitchNanos > bodySwitchInterval()) {
            lastBodySwitchNanos = now;
            bodyYawOffset = (RNG.nextDouble() - 0.5) * 0.6;   // ±0.3°
            bodyPitchOffset = (RNG.nextDouble() - 0.5) * 0.5; // ±0.25°
        }

        // Compose final deltas
        float finalYawDelta = rawYawDelta + wobbleYaw + (float) organicDrift
                + (float) bodyYawOffset * 0.3F;
        float finalPitchDelta = rawPitchDelta + wobblePitch + (float) bodyPitchOffset * 0.3F;

        return new HumanizedAimResult(finalYawDelta, finalPitchDelta, lerp);
    }

    private long bodySwitchInterval() {
        return 200_000_000L + RNG.nextLong(200_000_001L); // 200–400ms
    }

    public void reset() {
        phase = 0;
        lastBodySwitchNanos = 0;
    }

    /** Result of humanized aim calculation. */
    public record HumanizedAimResult(float yawDelta, float pitchDelta, float lerp) {}
}
