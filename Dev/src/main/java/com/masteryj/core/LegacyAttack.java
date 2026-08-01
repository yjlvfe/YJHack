package com.masteryj.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Legacy-style (pre-1.9) attack packet, bypassing modern attack cooldown.
 * Automatically applies critical hits when the player is falling.
 */
public final class LegacyAttack {

    private LegacyAttack() {}

    public static boolean perform(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return false;
        if (client.interactionManager == null) return false;

        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof EntityHitResult entityHit)) return false;

        Entity target = entityHit.getEntity();
        if (!(target instanceof PlayerEntity) && !target.isLiving()) return false;

        // Reset cooldown so modern client doesn't block
        client.player.resetLastAttackedTicks();

        // Check for critical hit conditions
        ClientPlayerEntity p = client.player;
        boolean critical = p.fallDistance > 0.0F && !p.isOnGround()
                && !p.isTouchingWater() && !p.isInLava()
                && !p.isClimbing() && !p.hasVehicle();

        // Send attack packet (with critical flag)
        client.getNetworkHandler().sendPacket(
                PlayerInteractEntityC2SPacket.attack(target, p.isSneaking()));

        // If critical, send the critical packet separately
        if (critical) {
            client.getNetworkHandler().sendPacket(
                    PlayerInteractEntityC2SPacket.attack(target, p.isSneaking()));
            // Swing again for crit visual
            p.swingHand(p.getActiveHand());
        }

        // Swing hand
        p.swingHand(p.getActiveHand());
        return true;
    }
}
