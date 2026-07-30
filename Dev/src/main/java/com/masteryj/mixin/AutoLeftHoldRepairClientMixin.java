package com.masteryj.mixin;

import com.masteryj.autoleft.AutoLeftHoldRepairClient;
import com.masteryj.core.PhysicalKeyBinding;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the repaired held-click path aligned with the user's attack binding. */
@Mixin(value = AutoLeftHoldRepairClient.class, remap = false)
public abstract class AutoLeftHoldRepairClientMixin {

    @Inject(method = "isMouseDown", at = @At("HEAD"), cancellable = true, remap = false)
    private void yjhack$useConfiguredAttackKey(MinecraftClient client, int button,
                                               CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(button == 0 && client != null && client.options != null
                && PhysicalKeyBinding.isPressed(client, client.options.attackKey));
    }
}
