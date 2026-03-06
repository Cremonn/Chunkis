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
 *
 * <p>
 * This class serves as the main entry point for the mod across both physical
 * client and dedicated server environments (Fabric "main" entrypoint).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 * <li><b>Common Registration:</b> Registers shared content such as network
 * payloads and commands.</li>
 * <li><b>Server-Side Logic:</b> Handles logic that runs on both singleplayer
 * and multiplayer servers, including MCA migration and chunk tracker
 * cleanup.</li>
 * </ul>
 *
 * <p>
 * For client-specific initialization (rendering, client packet handling),
 * see {@link ClientChunkisMod}.
 *
 * @author Liparakis
 * @version 1.1
 */
public class ChunkisMod implements ModInitializer {

    @Override
    public void onInitialize() {
        registerPayloads();
        registerCommands();
        registerEvents();
    }

    /**
     * Registers server lifecycle and world events.
     *
     * <ul>
     * <li>{@link ServerWorldEvents#LOAD} — triggers MCA-to-CIS migration for
     * each world dimension on load.</li>
     * <li>{@link ServerLifecycleEvents#SERVER_STOPPED} — clears the
     * {@link GlobalChunkTracker} to prevent memory leaks across singleplayer
     * sessions.</li>
     * </ul>
     */
    private void registerEvents() {
        ServerWorldEvents.LOAD.register((server, world) -> McaMigrator.migrateWorld(world));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> GlobalChunkTracker.clear());
    }

    /**
     * Registers network payload types for server-to-client communication.
     *
     * <p>
     * {@link ChunkDeltaPayload} carries serialized chunk delta data to connected
     * clients so they can apply the same modifications to their local chunk view.
     */
    private void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(ChunkDeltaPayload.ID, ChunkDeltaPayload.CODEC);
    }

    /**
     * Registers server-side commands via the Fabric command API.
     *
     * <p>
     * Delegates to {@link MigrationCommand#register} to add the {@code /migrate}
     * command for manual MCA migration triggering.
     */
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> MigrationCommand.register(dispatcher));
    }
}