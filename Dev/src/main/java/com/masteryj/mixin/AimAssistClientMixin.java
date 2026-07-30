package com.masteryj.mixin;

import com.masteryj.aimassist.AimAssistClient;
import com.masteryj.aimassist.AimAssistRangePolicy;
import com.masteryj.core.PhysicalKeyBinding;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the requested 5.5-block visual lock and immediately drops targets behind walls. */
@Mixin(value = AimAssistClient.class, remap = false)
public abstract class AimAssistClientMixin {

    @Shadow
    private PlayerEntity target;

    @Shadow
    private void clearTarget() {
    }

    @ModifyConstant(
            method = {"tickAimAssist", "findBestTarget", "isWithinLockDistance"},
            constant = @Constant(doubleValue = 16.0D),
            remap = false)
    private static double yjhack$useConfiguredAimDistance(double original) {
        return AimAssistRangePolicy.MAX_DISTANCE_SQUARED;
    }

    @Inject(method = "tickAimAssist", at = @At("HEAD"), remap = false)
    private void yjhack$dropOccludedTargetImmediately(MinecraftClient client, CallbackInfo ci) {
        if (target == null) return;
        if (client == null || client.player == null || client.world == null
                || target.getWorld() != client.world || !hasLineOfSight(client, target)) {
            clearTarget();
        }
    }

    @Inject(method = "isMouseDown", at = @At("HEAD"), cancellable = true, remap = false)
    private void yjhack$useMinecraftControlBindings(MinecraftClient client, int button,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (client == null || client.options == null) {
            cir.setReturnValue(false);
            return;
        }
        cir.setReturnValue(button == 0
                ? PhysicalKeyBinding.isPressed(client, client.options.attackKey)
                : button == 1 && PhysicalKeyBinding.isPressed(client, client.options.useKey));
    }

    private static boolean hasLineOfSight(MinecraftClient client, PlayerEntity candidate) {
        Vec3d start = client.player.getEyePos();
        Box box = candidate.getBoundingBox().expand(-0.03D);
        Vec3d center = box.getCenter();
        Vec3d chest = new Vec3d(center.x, box.minY + box.getLengthY() * 0.65D, center.z);
        Vec3d head = new Vec3d(center.x, box.minY + box.getLengthY() * 0.88D, center.z);
        for (Vec3d point : new Vec3d[]{chest, head, center}) {
            BlockHitResult hit = client.world.raycast(new RaycastContext(
                    start, point, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, client.player));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
}
