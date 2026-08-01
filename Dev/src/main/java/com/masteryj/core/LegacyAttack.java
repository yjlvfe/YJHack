package com.masteryj.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Legacy-style (pre-1.9) attack, bypassing modern attack cooldown.
 * Critical hits: send the critical packet BEFORE the attack packet
 * when the player is falling (not on ground, not in liquid, not climbing).
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

        ClientPlayerEntity p = client.player;

        // Reset cooldown so modern client doesn't block
        p.resetLastAttackedTicks();

        // Critical hit: must send critical packet BEFORE attack packet (1.8 protocol)
        boolean critical = p.fallDistance > 0.0F && !p.isOnGround()
                && !p.isTouchingWater() && !p.isInLava()
                && !p.isClimbing() && !p.hasVehicle();

        if (critical) {
            // Send critical hit packet first
            client.getNetworkHandler().sendPacket(
                    PlayerInteractEntityC2SPacket.attack(target, p.isSneaking()));
        }

        // Send normal attack packet
        client.getNetworkHandler().sendPacket(
                PlayerInteractEntityC2SPacket.attack(target, p.isSneaking()));

        // Swing hand
        p.swingHand(p.getActiveHand());
        return true;
    }
}
