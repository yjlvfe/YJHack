package com.masteryj.mixin;

import com.masteryj.aimassist.AimAssistClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records only attacks Minecraft actually accepted against a player. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientAttackMixin {

    @Shadow public HitResult crosshairTarget;

    @Inject(method = "doAttack", at = @At("RETURN"))
    private void yjhack$recordAcceptedPlayerAttack(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()
                && crosshairTarget instanceof EntityHitResult hit
                && hit.getEntity() instanceof PlayerEntity) {
            AimAssistClient.onAcceptedPlayerAttack();
        }
    }
}
