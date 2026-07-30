package com.masteryj.mixin;

import com.masteryj.core.ActionBudget;
import com.masteryj.core.DebugStats;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts a left action as emitted only when Minecraft accepts doAttack(). */
@Mixin(value = ActionBudget.class, remap = false)
public abstract class ActionBudgetMixin {

    @Inject(method = "emitVanillaAction", at = @At("HEAD"), cancellable = true, remap = false)
    private void yjhack$emitAndRecordAcceptedAction(MinecraftClient client,
                                                     ActionBudget.Module module,
                                                     CallbackInfo ci) {
        MinecraftClientInvoker invoker = (MinecraftClientInvoker) client;
        if (module == ActionBudget.Module.LEFT) {
            if (invoker.yjhack$invokeDoAttack()) {
                DebugStats.onAutoLeftPulse();
            }
        } else {
            invoker.yjhack$invokeDoItemUse();
            DebugStats.onAutoRightBlockPulse();
        }
        ci.cancel();
    }
}
