package io.liparakis.chunkis.network;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.codec.CisNetworkEncoder;
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
 * who receives vanilla chunk data also receives the corresponding Chunkis
 * delta.
 *
 * <p>
 * The send pipeline is:
 * <ol>
 * <li>Guard: chunk must implement {@link ChunkisDeltaDuck} and carry a
 * non-empty delta.</li>
 * <li>Capture: live block-entities and entities are snapshotted into the
 * delta.</li>
 * <li>Encode: delta is serialized by the thread-local
 * {@link CisNetworkEncoder}.</li>
 * <li>Size-check: payloads exceeding {@value #MAX_DELTA_SIZE} bytes are dropped
 * with a log.</li>
 * <li>Send: encoded bytes are wrapped in a {@link ChunkDeltaPayload} (with
 * optional
 * compression) and sent via {@link ServerPlayNetworking}.</li>
 * </ol>
 *
 * @author Liparakis
 * @version 1.2
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
    private static final ThreadLocal<CisNetworkEncoder> ENCODER_POOL = ThreadLocal
            .withInitial(FabricNetworkCodecFactory::createEncoder);

    private ChunkisNetworking() {
        throw new AssertionError("Utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Encodes the chunk's Chunkis delta and sends it to {@code player}.
     *
     * @param player the player to send to (must not be null)
     * @param chunk  the chunk whose delta should be sent (must not be null)
     */
    public static void sendDelta(final ServerPlayerEntity player, final WorldChunk chunk) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");

        final ChunkDelta<?, ?> delta = extractDelta(chunk);
        if (delta == null)
            return;
        if (isPlayerUnavailable(player))
            return;

        encodAndSend(player, chunk.getPos(), delta);
    }

    // -------------------------------------------------------------------------
    // Delta extraction
    // -------------------------------------------------------------------------

    /**
     * Returns the non-empty delta from the chunk's duck interface, or null if
     * the chunk does not implement {@link ChunkisDeltaDuck} or its delta is absent.
     *
     * @param chunk the chunk to inspect
     * @return the active delta, or null
     */
    private static ChunkDelta<?, ?> extractDelta(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck))
            return null;

        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        return (delta == null || delta.isEmpty()) ? null : delta;
    }

    // Method captureCurrentState removed because we are using proactive capture
    // now.

    // -------------------------------------------------------------------------
    // Encode and send
    // -------------------------------------------------------------------------

    /**
     * Encodes the delta, enforces the size limit, creates a
     * {@link ChunkDeltaPayload},
     * and sends it to the player. Any exception during encoding or sending is
     * caught
     * and logged so a single bad chunk cannot disrupt the packet pipeline.
     *
     * @param player the recipient
     * @param pos    the chunk position (used for logging)
     * @param delta  the delta to encode and send
     */
    @SuppressWarnings("unchecked")
    private static void encodAndSend(
            final ServerPlayerEntity player,
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta) {

        try {
            final byte[] rawData = ENCODER_POOL.get().encode(delta);

            if (exceedsSizeLimit(rawData)) {
                Chunkis.LOGGER.error(
                        "Chunkis: Delta too large for chunk ({}, {}): {} bytes — skipping",
                        pos.x, pos.z, rawData.length);
                return;
            }

            final ChunkDeltaPayload payload = ChunkDeltaPayload.create(rawData, pos.x, pos.z);
            ServerPlayNetworking.send(player, payload);

        } catch (final Exception e) {
            Chunkis.LOGGER.error("Chunkis: Failed to send delta for chunk ({}, {})", pos.x, pos.z, e);
        }
    }

    // -------------------------------------------------------------------------
    // Guard predicates
    // -------------------------------------------------------------------------

    /**
     * Returns true if the player is no longer reachable (removed or disconnected).
     *
     * @param player the player to check
     * @return true if the send should be aborted
     */
    private static boolean isPlayerUnavailable(final ServerPlayerEntity player) {
        return player.isRemoved() || player.isDisconnected();
    }

    /**
     * Returns true if the raw encoded data exceeds {@value #MAX_DELTA_SIZE} bytes.
     *
     * @param data the encoded delta bytes
     * @return true if the payload is too large to send
     */
    private static boolean exceedsSizeLimit(final byte[] data) {
        return data.length > MAX_DELTA_SIZE;
    }
}