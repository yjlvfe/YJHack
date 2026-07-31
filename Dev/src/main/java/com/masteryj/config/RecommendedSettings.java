package com.masteryj.config;

/**
 * Conservative, documented defaults for a modern 1.21.5 client connected to a legacy-style
 * 1.8.9 combat server through a protocol translation layer.
 *
 * <p>These values are attempt/input defaults, not promises about server-accepted attacks,
 * damage, knockback, or placed blocks. The server, its tick loop, latency, plugins, and
 * protocol translator remain authoritative.
 */
public final class RecommendedSettings {

    public static final int AUTO_LEFT_CPS = 12;
    public static final int AUTO_RIGHT_CPS = 10;

    public static final int NINJA_SWITCH_DELAY_MS = 120;
    public static final boolean NINJA_AUTO_SWITCH = true;

    public static final double AIM_MAX_RANGE = 3.5D;
    public static final float AIM_SPEED = 0.22F;
    public static final float AIM_SMOOTHNESS = 0.62F;
    public static final float AIM_FOV = 70.0F;
    public static final boolean AIM_STICKY_LOCK = true;
    public static final boolean AIM_LINE_OF_SIGHT = true;
    public static final boolean AIM_BED_LOCK = true;

    public static final double TRACKER_RANGE = 48.0D;
    public static final boolean TRACKER_IGNORE_TEAM = true;
    public static final int TRACKER_HUD_X = 8;
    public static final int TRACKER_HUD_Y = 8;

    private RecommendedSettings() {
    }
}
