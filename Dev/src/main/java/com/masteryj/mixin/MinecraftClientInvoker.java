package com.masteryj.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftClient.class)
public interface MinecraftClientInvoker {

    @Invoker("doAttack")
    boolean yjhack$invokeDoAttack();

    @Invoker("doItemUse")
    void yjhack$invokeDoItemUse();
}
