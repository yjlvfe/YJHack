package com.masteryj.mixin;

import com.masteryj.modgui.ModGuiClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Changes only the default GUI key; the binding remains editable in Minecraft Controls. */
@Mixin(value = ModGuiClient.class, remap = false)
public abstract class ModGuiClientMixin {

    @ModifyConstant(method = "onInitializeClient", constant = @Constant(intValue = 344), remap = false)
    private int yjhack$defaultGuiKey(int original) {
        return GLFW.GLFW_KEY_H;
    }
}
