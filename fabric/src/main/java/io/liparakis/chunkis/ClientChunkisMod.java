package io.liparakis.chunkis;

import io.liparakis.chunkis.client.ClientDeltaMetrics;
import io.liparakis.chunkis.client.ClientDeltaNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side entry point for the Chunkis mod.
 *
 * <p>
 * Delegates all substantive work to focused handler classes:
 * <ul>
 * <li>{@link ClientDeltaNetworking} — packet receiver, decoder, and
 * visitor</li>
 * <li>{@link ClientDeltaMetrics} — performance counters and error
 * rate-limiting</li>
 * </ul>
 *
 * @author Liparakis
 * @version 1.1
 */
@Environment(EnvType.CLIENT)
public class ClientChunkisMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Chunkis.LOGGER.info("Chunkis Client initializing...");

        ClientDeltaNetworking.register();

        if (ClientDeltaMetrics.ENABLED) Chunkis.LOGGER.info("Chunkis Client initialized — metrics enabled.");
        else Chunkis.LOGGER.info("Chunkis Client initialized.");
    }
}