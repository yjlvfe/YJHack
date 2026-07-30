package com.masteryj.aimassist;

/** Shared, testable AimAssist distance and visibility policy. */
public final class AimAssistRangePolicy {

    public static final double MAX_DISTANCE = 5.5D;
    public static final double MAX_DISTANCE_SQUARED = MAX_DISTANCE * MAX_DISTANCE;

    private AimAssistRangePolicy() {
    }

    public static boolean isWithinDistance(double distanceSquared) {
        return Double.isFinite(distanceSquared) && distanceSquared <= MAX_DISTANCE_SQUARED;
    }

    public static boolean shouldDropLock(boolean hasTarget, boolean hasLineOfSight) {
        return hasTarget && !hasLineOfSight;
    }
}
