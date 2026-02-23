package io.liparakis.chunkis;

import io.liparakis.chunkis.command.MigrationCommand;
import io.liparakis.chunkis.network.ChunkDeltaPayload;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import io.liparakis.chunkis.util.McaMigrator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Common initialization for the Chunkis mod.
 * <p>
 * This class serves as the main entry point for the mod across both physical
 * client
 * and dedicated server environments (Fabric "main" entrypoint).
 * </p>
 *
 * <h2>Responsibilities:</h2>
 * <ul>
 * <li><b>Common Registration:</b> Registers shared content like packets,
 * blocks, items, etc.</li>
 * <li><b>Server-Side Logic:</b> Handles logic that runs on both singleplayer
 * and multiplayer servers.</li>
 * </ul>
 *
 * <p>
 * For client-specific initialization (rendering, client packet handling),
 * see {@link ClientChunkisMod}.
 * </p>
 *
 * @author Liparakis
 * @version 1.0
 */
public class ChunkisMod implements ModInitializer {

    @Override
    public void onInitialize() {
        registerPayloads();
        registerCommands();
        registerEvents();
    }

    private void registerEvents() {
        ServerWorldEvents.LOAD
                .register((server, world) -> McaMigrator.migrateWorld(world));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> GlobalChunkTracker.clear());
    }

    private void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(
                ChunkDeltaPayload.ID,
                ChunkDeltaPayload.CODEC);
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment)
                        -> MigrationCommand.register(dispatcher));
    }
}
