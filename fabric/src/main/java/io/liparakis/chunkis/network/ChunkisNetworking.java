package io.liparakis.chunkis.network;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.codec.stream.CisNetworkEncoder;
import io.liparakis.chunkis.model.ChunkDelta;
import io.liparakis.chunkis.util.FabricNetworkCodecFactory;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Objects;

/**
 * Lightweight networking handler that sends Chunkis chunk deltas to players.
 *
 * <p>
 * Called from {@link io.liparakis.chunkis.mixin.network.ChunkHolderMixin}
 * whenever a {@code ChunkDataS2CPacket} is about to be sent, so every player
 * who receives vanilla chunk data also receives the corresponding Chunkis delta.
 *
 * <p>
 * The send pipeline is:
 * <ol>
 * <li>Guard: chunk must implement {@link ChunkisDeltaDuck} and carry a
 * non-empty delta.</li>
 * <li>Guard: player must still be reachable (not removed or disconnected).</li>
 * <li>Encode: delta is serialized by the thread-local {@link CisNetworkEncoder}.</li>
 * <li>Size-check: payloads exceeding {@value #MAX_DELTA_SIZE} bytes are dropped
 * with a log.</li>
 * <li>Send: encoded bytes are wrapped in a {@link ChunkDeltaPayload} (with
 * optional compression) and sent via {@link ServerPlayNetworking}.</li>
 * </ol>
 *
 * @author Liparakis
 * @version 1.3
 */
public final class ChunkisNetworking {

    /**
     * Hard ceiling on outgoing delta size. Payloads larger than this are dropped.
     */
    private static final int MAX_DELTA_SIZE = 1_024_000; // 1 MB

    /**
     * Thread-local encoder — each thread reuses a single instance, eliminating
     * per-call allocation on the packet-send hot path.
     */
    @SuppressWarnings("rawtypes")
    private static final ThreadLocal<CisNetworkEncoder> ENCODER_POOL =
            ThreadLocal.withInitial(FabricNetworkCodecFactory::createEncoder);

    private ChunkisNetworking() {
        throw new AssertionError("Utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Encodes the chunk's Chunkis delta and sends it to {@code player}.
     *
     * <p>
     * No-ops if the chunk does not carry a delta, or if the player is no longer
     * reachable. Any exception during encoding or sending is caught and logged
     * so a single bad chunk cannot disrupt the packet pipeline.
     *
     * @param player the player to send to (must not be null)
     * @param chunk  the chunk whose delta should be sent (must not be null)
     */
    public static void sendDelta(final ServerPlayerEntity player, final WorldChunk chunk) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");

        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) return;
        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        if (delta == null || delta.isEmpty()) return;

        if (player.isRemoved() || player.isDisconnected()) return;

        encodeAndSend(player, chunk.getPos(), delta);
    }

    // -------------------------------------------------------------------------
    // Encode and send
    // -------------------------------------------------------------------------

    /**
     * Encodes the delta, enforces the size limit, creates a {@link ChunkDeltaPayload},
     * and sends it to the player.
     *
     * <p>
     * Payloads exceeding {@value #MAX_DELTA_SIZE} bytes are dropped with an error
     * log rather than sent, to prevent overloading the client connection. Any
     * exception during encoding or sending is caught and logged so a single bad
     * chunk cannot disrupt the broader packet pipeline.
     *
     * @param player the recipient
     * @param pos    the chunk position (used only for logging)
     * @param delta  the delta to encode and send
     */
    @SuppressWarnings("unchecked")
    private static void encodeAndSend(
            final ServerPlayerEntity player,
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta) {

        try {
            final byte[] rawData = ENCODER_POOL.get().encode(delta);

            if (rawData.length > MAX_DELTA_SIZE) {
                Chunkis.LOGGER.error(
                        "Chunkis: Delta too large for chunk ({}, {}): {} bytes — skipping",
                        pos.x, pos.z, rawData.length);
                return;
            }

            ServerPlayNetworking.send(player, ChunkDeltaPayload.create(rawData, pos.x, pos.z));

        } catch (final Exception e) {
            Chunkis.LOGGER.error("Chunkis: Failed to send delta for chunk ({}, {})", pos.x, pos.z, e);
        }
    }
}