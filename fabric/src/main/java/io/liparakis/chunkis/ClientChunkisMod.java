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
 * <li>{@link ClientDeltaNetworking} — packet receiver, decoder, and visitor</li>
 * <li>{@link ClientDeltaMetrics} — performance counters and error rate-limiting</li>
 * </ul>
 *
 * @author Liparakis
 * @version 1.2
 */
@Environment(EnvType.CLIENT)
public class ClientChunkisMod implements ClientModInitializer {

    /**
     * Initializes all client-side systems.
     *
     * <p>
     * Registers the {@link ClientDeltaNetworking} packet handler, then logs
     * a startup message that includes a metrics-enabled indicator when
     * {@link ClientDeltaMetrics#ENABLED} is true.
     */
    @Override
    public void onInitializeClient() {
        Chunkis.LOGGER.info("Chunkis client initializing...");
        ClientDeltaNetworking.register();
        Chunkis.LOGGER.info("Chunkis client initialized{}.",
                ClientDeltaMetrics.ENABLED ? " — metrics enabled" : "");
    }
}