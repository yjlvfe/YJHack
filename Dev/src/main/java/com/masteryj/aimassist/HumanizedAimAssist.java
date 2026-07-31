package com.masteryj.aimassist;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Humanizes AimAssist movement — no missed shots, just human-like imperfection.
 *
 * <p>Four independent dimensions of humanization:
 * <ol>
 *   <li>Micro-wobble: tiny random offset every tick</li>
 *   <li>Inconsistent speed: lerp varies per tick</li>
 *   <li>Organic path: gentle sine-wave deviation while tracking</li>
 *   <li>Body variation: aim point shifts slightly on the target's hitbox.
 *       A new target gets a fresh random offset. The offset is stable
 *       for the same target within a session, with minor periodic drift.</li>
 * </ol>
 *
 * <p>No acquisition delay — aim begins immediately when the target is found.
 */
public final class HumanizedAimAssist {

    private static final RandomGenerator RNG =
            RandomGeneratorFactory.getDefault().create();

    private double phase;
    private long nextBodySwitchNanos;
    private int lastTargetId = -1;
    private double bodyYawOffset;
    private double bodyPitchOffset;

    /**
     * Apply humanized corrections to aim movement.
     *
     * @param yawDelta    raw yaw delta from aim calculation
     * @param pitchDelta  raw pitch delta from aim calculation
     * @param baseSpeed   raw speed factor (0.005–1.0)
     * @param targetId    entity ID so the same target keeps a consistent body offset
     * @return corrected deltas and lerp
     */
    public HumanizedAimResult apply(
            float yawDelta, float pitchDelta,
            float baseSpeed, int targetId) {

        // 1. Inconsistent speed: vary ±15% per tick
        float speedVar = 0.85F + RNG.nextFloat() * 0.30F;
        float lerp = Math.min(1.0F, baseSpeed * speedVar);

        // 2. Micro-wobble
        float wobbleYaw = (RNG.nextFloat() - 0.5F) * 0.6F;   // ±0.3°
        float wobblePitch = (RNG.nextFloat() - 0.5F) * 0.4F; // ±0.2°

        // 3. Organic path: gentle sine drift
        phase += 0.15 + RNG.nextDouble() * 0.05;
        double organicDrift = Math.sin(phase) * 0.4;

        // 4. Body variation — stable offset per target, with minor periodic drift
        updateBodyOffsets(targetId);

        float finalYawDelta = yawDelta + wobbleYaw + (float) organicDrift
                + (float) bodyYawOffset;
        float finalPitchDelta = pitchDelta + wobblePitch + (float) bodyPitchOffset;

        return new HumanizedAimResult(finalYawDelta, finalPitchDelta, lerp);
    }

    private void updateBodyOffsets(int targetId) {
        long now = System.nanoTime();

        if (targetId != lastTargetId) {
            // New target — generate fresh body aim point
            lastTargetId = targetId;
            bodyYawOffset = (RNG.nextDouble() - 0.5) * 0.5;
            bodyPitchOffset = (RNG.nextDouble() - 0.5) * 0.4;
            // Schedule next periodic drift
            nextBodySwitchNanos = now + bodySwitchInterval();
            return;
        }

        // Periodic drift within the same target
        if (now >= nextBodySwitchNanos) {
            // Small wander, not full reset — same body area, slight movement
            bodyYawOffset += (RNG.nextDouble() - 0.5) * 0.2;
            bodyPitchOffset += (RNG.nextDouble() - 0.5) * 0.15;
            bodyYawOffset = Math.max(-0.5, Math.min(0.5, bodyYawOffset));
            bodyPitchOffset = Math.max(-0.4, Math.min(0.4, bodyPitchOffset));
            // Schedule next drift
            nextBodySwitchNanos = now + bodySwitchInterval();
        }
    }

    private long bodySwitchInterval() {
        return 200_000_000L + RNG.nextLong(200_000_001L); // 200–400ms
    }

    /** Reset all state — call on world change, disable, death, or target change. */
    public void reset() {
        phase = 0;
        lastTargetId = -1;
        nextBodySwitchNanos = 0;
        bodyYawOffset = 0;
        bodyPitchOffset = 0;
    }

    // Testing accessors
    double phase() { return phase; }
    int lastTargetId() { return lastTargetId; }
    double bodyYawOffset() { return bodyYawOffset; }
    double bodyPitchOffset() { return bodyPitchOffset; }

    public record HumanizedAimResult(float yawDelta, float pitchDelta, float lerp) {}
}
