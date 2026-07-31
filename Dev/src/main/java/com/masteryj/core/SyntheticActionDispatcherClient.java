package com.masteryj.core;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.World;

/**
 * Queues synthetic key presses immediately before vanilla's client tick handles input.
 *
 * <p>AutoLeft and AutoRight submit work at END_CLIENT_TICK. The following
 * START_CLIENT_TICK validates the latest gameplay state and places at most one press per module in
 * Minecraft's own KeyBinding queue. Vanilla then performs the actual attack/use interaction,
 * preserving normal cooldowns, sequence handling, server packets, and prediction reconciliation.
 */
public final class SyntheticActionDispatcherClient implements ClientModInitializer {

    private World lastWorld;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.world != lastWorld) {
                lastWorld = client.world;
                ActionBudget.INSTANCE.resetAll();
            }
            if (client.world == null || client.player == null) {
                ActionBudget.INSTANCE.resetAll();
                return;
            }
            ActionBudget.INSTANCE.flush(System.nanoTime());
        });
    }
}
