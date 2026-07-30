package com.masteryj.core;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.World;

/** Flushes AutoLeft/AutoRight requests once at the start of each real client tick. */
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
            ActionBudget.INSTANCE.flush(client, System.nanoTime());
        });
    }
}
