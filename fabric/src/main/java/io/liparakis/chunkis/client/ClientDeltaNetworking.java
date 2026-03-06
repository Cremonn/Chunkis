package io.liparakis.chunkis.client;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.codec.stream.CisNetworkDecoder;
import io.liparakis.chunkis.model.ChunkDelta;
import io.liparakis.chunkis.network.ChunkDeltaPayload;
import io.liparakis.chunkis.util.FabricNetworkCodecFactory;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Property;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Registers the {@link ChunkDeltaPayload} receiver and owns the codec/visitor
 * thread-locals needed to decode and apply incoming deltas.
 *
 * <p>
 * Call {@link #register()} once during client initialisation. The receiver
 * schedules all world-mutation work on the main client thread; networking-thread
 * code is intentionally kept minimal (early validation and metrics recording only).
 *
 * <p>
 * No world, chunk, or block references are retained beyond the scope of
 * each method call.
 *
 * @author Liparakis
 * @version 1.1
 */
@Environment(EnvType.CLIENT)
public final class ClientDeltaNetworking {

    /**
     * Thread-local decoder — each thread gets its own instance with pre-allocated
     * buffers, eliminating allocation overhead on the packet-receive hot path.
     */
    private static final ThreadLocal<CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound>> DECODER =
            ThreadLocal.withInitial(FabricNetworkCodecFactory::createDecoder);

    /**
     * Thread-local visitor — reused across packets to avoid the ~80-byte
     * per-packet allocation of visitor state.
     */
    private static final ThreadLocal<ClientDeltaVisitor> VISITOR =
            ThreadLocal.withInitial(ClientDeltaVisitor::new);

    /**
     * Guards the "chunk does not implement ChunkisDeltaDuck" warning so it is
     * emitted at most once per session, preventing log flooding on broken chunks.
     */
    private static volatile boolean typeWarningLogged = false;

    private ClientDeltaNetworking() {
        throw new AssertionError("Utility class");
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers the global {@link ChunkDeltaPayload} receiver and the disconnect
     * cleanup hook. Must be called on the main thread during client initialisation.
     */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                ChunkDeltaPayload.ID,
                ClientDeltaNetworking::handleIncomingPayload);

        // Release thread-local decoder/visitor state on disconnect so Netty
        // threads do not hold references to stale world or decoder state.
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> cleanupThreadLocals());
    }

    // -------------------------------------------------------------------------
    // Networking-thread handler (keep minimal — validate + schedule only)
    // -------------------------------------------------------------------------

    /**
     * Validates the incoming payload on the networking thread and, if valid,
     * schedules delta processing on the main client thread.
     *
     * @param payload the raw incoming packet
     * @param context the networking context providing the client instance
     */
    private static void handleIncomingPayload(
            final ChunkDeltaPayload payload,
            final ClientPlayNetworking.Context context) {

        final byte[] data = payload.data();

        if (isInvalidPayload(data)) {
            ClientDeltaMetrics.logErrorThrottled(
                    () -> "Received invalid ChunkDeltaPayload: null or empty data");
            return;
        }

        if (ClientDeltaMetrics.ENABLED) {
            ClientDeltaMetrics.recordPacket(data.length);
        }

        final var client = context.client();
        client.execute(() -> processChunkDelta(payload, client.world));
    }

    // -------------------------------------------------------------------------
    // Main-thread processing
    // -------------------------------------------------------------------------

    /**
     * Decodes the payload and applies its delta to the client world.
     * Always runs on the main client thread.
     *
     * @param payload the incoming packet (data already validated on networking thread)
     * @param world   the current client world; may be null during disconnect
     */
    private static void processChunkDelta(final ChunkDeltaPayload payload, final ClientWorld world) {
        if (world == null) return;

        final long startNanos = captureStartTime();

        try {
            applyPayloadToWorld(payload, world);
            recordMetricsIfEnabled(payload, startNanos);

            Chunkis.LOGGER.debug("Applied ChunkDelta ({},{}) — {} bytes, {} blocks",
                    payload.chunkX(), payload.chunkZ(),
                    payload.data().length,
                    DECODER.get().decode(payload.data()).getBlockInstructions().size());

        } catch (final Exception e) {
            ClientDeltaMetrics.logErrorThrottled(() -> String.format(
                    "Decode/apply failed for chunk (%d,%d) — %d bytes",
                    payload.chunkX(), payload.chunkZ(),
                    payload.data() != null ? payload.data().length : 0), e);
        }
    }

    /**
     * Resolves the target chunk, decodes the delta, and applies it.
     *
     * @param payload the incoming packet
     * @param world   the current client world
     * @throws Exception if decoding or application fails
     */
    private static void applyPayloadToWorld(
            final ChunkDeltaPayload payload,
            final ClientWorld world) throws Exception {

        final int chunkX = payload.chunkX();
        final int chunkZ = payload.chunkZ();

        final WorldChunk chunk = world.getChunk(chunkX, chunkZ);
        if (!isChunkisDuck(chunk, chunkX, chunkZ)) return;

        final ChunkDelta<BlockState, NbtCompound> receivedDelta = DECODER.get().decode(payload.data());

        @SuppressWarnings("unchecked")
        final ChunkDelta<BlockState, NbtCompound> clientDelta =
                (ChunkDelta<BlockState, NbtCompound>) ((ChunkisDeltaDuck) chunk).chunkis$getDelta();

        applyDelta(clientDelta, receivedDelta, world, chunkX, chunkZ);
    }

    /**
     * Applies {@code receivedDelta} to the client world and delta tracker using
     * the thread-local {@link ClientDeltaVisitor}.
     *
     * @param clientDelta   the client-side delta tracker to keep in sync
     * @param receivedDelta the decoded delta received from the server
     * @param world         the client world to mutate
     * @param chunkX        chunk X coordinate
     * @param chunkZ        chunk Z coordinate
     */
    private static void applyDelta(
            final ChunkDelta<BlockState, NbtCompound> clientDelta,
            final ChunkDelta<BlockState, NbtCompound> receivedDelta,
            final ClientWorld world,
            final int chunkX,
            final int chunkZ) {

        final ClientDeltaVisitor visitor = VISITOR.get();
        visitor.reset(clientDelta, world, chunkX, chunkZ);
        receivedDelta.accept(visitor);
    }

    // -------------------------------------------------------------------------
    // Metrics helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the current nanosecond timestamp if metrics are enabled, otherwise 0.
     * Avoids a {@link System#nanoTime()} call when metrics are disabled.
     *
     * @return nanosecond start time, or 0 if metrics are disabled
     */
    private static long captureStartTime() {
        return ClientDeltaMetrics.ENABLED ? System.nanoTime() : 0L;
    }

    /**
     * Records decode time and block change counts, and emits a periodic summary
     * every 1024 packets. No-ops when metrics are disabled.
     *
     * @param payload    the processed payload (used for block count lookup)
     * @param startNanos the nanosecond timestamp captured before processing
     */
    private static void recordMetricsIfEnabled(
            final ChunkDeltaPayload payload,
            final long startNanos) throws Exception {

        if (!ClientDeltaMetrics.ENABLED) return;

        ClientDeltaMetrics.recordDecode(System.nanoTime() - startNanos);
        ClientDeltaMetrics.recordBlocksChanged(
                DECODER.get().decode(payload.data()).getBlockInstructions().size());

        // Bit-mask avoids modulo overhead; emits summary every 1024 packets.
        if ((ClientDeltaMetrics.packetCount() & 0x3FF) == 0) {
            ClientDeltaMetrics.logSummary();
        }
    }

    // -------------------------------------------------------------------------
    // Guard predicates
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given data byte array is null or empty.
     *
     * @param data the payload data to check
     * @return true if the payload should be rejected
     */
    private static boolean isInvalidPayload(final byte[] data) {
        return data == null || data.length == 0;
    }

    /**
     * Returns true if the given chunk implements {@link ChunkisDeltaDuck}.
     * Logs a one-time warning if it does not.
     *
     * @param chunk  the chunk to test
     * @param chunkX chunk X coordinate (for the warning message)
     * @param chunkZ chunk Z coordinate (for the warning message)
     * @return true if the chunk can carry a delta
     */
    private static boolean isChunkisDuck(
            final WorldChunk chunk,
            final int chunkX,
            final int chunkZ) {

        if (chunk instanceof ChunkisDeltaDuck) return true;

        logTypeWarningOnce(chunk, chunkX, chunkZ);
        return false;
    }

    /**
     * Emits the "chunk does not implement ChunkisDeltaDuck" warning at most once
     * per session, guarded by the {@link #typeWarningLogged} volatile flag.
     *
     * @param chunk  the offending chunk
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    private static void logTypeWarningOnce(
            final WorldChunk chunk,
            final int chunkX,
            final int chunkZ) {

        if (typeWarningLogged) return;
        typeWarningLogged = true;

        Chunkis.LOGGER.warn(
                "Chunkis: Chunk at ({},{}) does not implement ChunkisDeltaDuck: {}. " +
                        "(Warning shown once only.)",
                chunkX, chunkZ, chunk.getClass().getName());
    }

    // -------------------------------------------------------------------------
    // Thread-local lifecycle
    // -------------------------------------------------------------------------

    /**
     * Releases thread-local decoder and visitor resources.
     * Called on client disconnect to prevent Netty thread-pool memory leaks
     * from stale decoder/visitor instances holding world state references.
     */
    private static void cleanupThreadLocals() {
        DECODER.remove();
        VISITOR.remove();
        Chunkis.LOGGER.debug("Chunkis: Cleaned up client thread-local resources");
    }
}