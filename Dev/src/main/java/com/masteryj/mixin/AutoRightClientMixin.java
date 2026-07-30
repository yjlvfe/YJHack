package com.masteryj.mixin;

import com.masteryj.autoright.AutoRightClient;
import com.masteryj.core.PhysicalKeyBinding;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes AutoRight follow the use control selected in Minecraft Controls. */
@Mixin(value = AutoRightClient.class, remap = false)
public abstract class AutoRightClientMixin {

    @Inject(method = "isMouseDown", at = @At("HEAD"), cancellable = true, remap = false)
    private void yjhack$useConfiguredUseKey(MinecraftClient client, int button,
                                            CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(button == 1 && client != null && client.options != null
                && PhysicalKeyBinding.isPressed(client, client.options.useKey));
    }

    @Inject(method = "suppressUseKey", at = @At("HEAD"), cancellable = true, remap = false)
    private void yjhack$suppressConfiguredUseKey(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            client.options.useKey.setPressed(false);
        }
        ci.cancel();
    }
}
