package com.masteryj.config;

import com.masteryj.aimassist.AimAssistClient;
import com.masteryj.autoleft.AutoLeftClient;
import com.masteryj.autoright.AutoRightClient;
import com.masteryj.ninjabridge.NinjaBridgeClient;
import com.masteryj.tracker.TrackerClient;

/** Fresh recommended config factories. No returned instance is shared. */
public final class RecommendedProfiles {

    private RecommendedProfiles() {
    }

    public static AutoLeftClient.Config autoLeft() {
        return AutoLeftClient.recommendedDefaults();
    }

    public static AutoRightClient.Config autoRight() {
        return AutoRightClient.recommendedDefaults();
    }

    public static NinjaBridgeClient.Config ninjaBridge() {
        return NinjaBridgeClient.recommendedDefaults();
    }

    public static AimAssistClient.Config aimAssist() {
        return AimAssistClient.recommendedDefaults();
    }

    public static TrackerClient.Config tracker() {
        TrackerClient.Config cfg = new TrackerClient.Config();
        cfg.enabled = false;
        cfg.toggleKeyCode = -1;
        cfg.ignoreOwnTeam = RecommendedSettings.TRACKER_IGNORE_TEAM;
        cfg.range = RecommendedSettings.TRACKER_RANGE;
        cfg.hudOffsetX = RecommendedSettings.TRACKER_HUD_X;
        cfg.hudY = RecommendedSettings.TRACKER_HUD_Y;
        cfg.normalize();
        return cfg;
    }
}
