package com.masteryj.core;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.World;

/**
 * Flushes AutoLeft/AutoRight requests once at the end of each real client tick.
 *
 * <p>Running after vanilla input handling avoids racing Minecraft's own attack/use processing.
 * Requests are still submitted for the following tick, guards are re-checked immediately before
 * emission, and stale work is dropped rather than replayed.
 */
public final class SyntheticActionDispatcherClient implements ClientModInitializer {

    private World lastWorld;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != lastWorld) {
                lastWorld = client.world;
                ActionBudget.INSTANCE.resetAll();
            }
            if (client.world == null || client.player == null) {
                ActionBudget.INSTANCE.resetAll();
                return;
            }
            ActionBudget.INSTANCE.flush(client, System.nanoTime());
        });
    }
}
