package com.masteryj.aimassist;

/** Hard legal boundary for AimAssist target retention. This never changes attack reach. */
public final class AimAssistRangePolicy {

    public static final double ABSOLUTE_MAX_DISTANCE = 3.5D;
    public static final double ABSOLUTE_MAX_DISTANCE_SQUARED =
            ABSOLUTE_MAX_DISTANCE * ABSOLUTE_MAX_DISTANCE;

    private AimAssistRangePolicy() {
    }

    public static double clampConfiguredDistance(double distance) {
        if (!Double.isFinite(distance)) return ABSOLUTE_MAX_DISTANCE;
        return Math.max(1.0D, Math.min(ABSOLUTE_MAX_DISTANCE, distance));
    }

    public static boolean isWithinDistance(double distanceSquared, double configuredDistance) {
        double clamped = clampConfiguredDistance(configuredDistance);
        return Double.isFinite(distanceSquared) && distanceSquared <= clamped * clamped;
    }

    public static boolean isWithinAbsoluteDistance(double distanceSquared) {
        return Double.isFinite(distanceSquared)
                && distanceSquared <= ABSOLUTE_MAX_DISTANCE_SQUARED;
    }

    public static boolean shouldDropLock(boolean hasTarget, boolean hasLineOfSight) {
        return hasTarget && !hasLineOfSight;
    }
}
