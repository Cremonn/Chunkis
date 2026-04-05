package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.util.CisNbtUtil;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@link ChunkSerializer#deserialize} to restore Chunkis delta data
 * into freshly deserialized {@link ProtoChunk} instances.
 *
 * <p>
 * Injected at {@code RETURN} so the vanilla deserialization path completes first.
 * If the resulting NBT contains the Chunkis marker key, the delta is loaded via a
 * memory-first, disk-fallback strategy and attached to the proto chunk. The chunk
 * status is then reset to {@link ChunkStatus#EMPTY} so the worldgen pipeline
 * re-runs and applies the delta on top of fresh terrain.
 *
 * @author Liparakis
 * @version 1.1
 */
@Mixin(ChunkSerializer.class)
public class ChunkSerializerMixin {

    // -------------------------------------------------------------------------
    // Injection
    // -------------------------------------------------------------------------

    /**
     * Intercepts the return of {@code ChunkSerializer#deserialize} to restore
     * any Chunkis delta attached to the chunk.
     *
     * @param world      the server world context
     * @param poiStorage point of interest storage (unused by this mixin)
     * @param key        storage key for the chunk (unused by this mixin)
     * @param pos        the chunk position being deserialized
     * @param nbt        the raw NBT read from storage
     * @param cir        callback holding the deserialized {@link ProtoChunk}
     */
    @Inject(method = "deserialize", at = @At("RETURN"))
    private static void chunkis$onDeserialize(
            final ServerWorld world,
            final PointOfInterestStorage poiStorage,
            final StorageKey key,
            final ChunkPos pos,
            final NbtCompound nbt,
            final CallbackInfoReturnable<ProtoChunk> cir) {

        if (!hasChunkisData(nbt)) return;

        final ProtoChunk chunk = cir.getReturnValue();
        if (chunk == null) return;

        restoreChunkDelta(world, pos, nbt, chunk);
    }

    // -------------------------------------------------------------------------
    // Restoration orchestration
    // -------------------------------------------------------------------------

    /**
     * Loads the delta for the given position and, if non-empty, attaches it to
     * the chunk and resets its status for worldgen re-application.
     *
     * @param world the server world (for disk storage access)
     * @param pos   the chunk position
     * @param chunk the newly deserialized proto chunk
     */
    @Unique
    private static void restoreChunkDelta(
            final ServerWorld world,
            final ChunkPos pos,
            final NbtCompound nbt,
            final ProtoChunk chunk) {

        final ChunkDelta<?, ?> delta = loadDelta(pos, world);
        if (isDeltaAbsent(delta)) return;
        delta.setSuppressInitialRepopulation(CisNbtUtil.shouldSuppressInitialRepopulation(nbt, delta));

        attachDeltaToChunk(chunk, delta);
        resetChunkStatus(chunk);
    }

    // -------------------------------------------------------------------------
    // Delta loading — memory-first, disk-fallback
    // -------------------------------------------------------------------------

    /**
     * Loads the delta for the given chunk position using a two-tier strategy:
     * <ol>
     * <li>In-memory {@link GlobalChunkTracker} — catches deltas modified since
     *     the last disk flush.</li>
     * <li>Persistent CIS storage — disk fallback for cold loads.</li>
     * </ol>
     *
     * @param pos   the chunk position
     * @param world the server world (for disk storage access)
     * @return the loaded delta, or null if none exists
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDelta(final ChunkPos pos, final ServerWorld world) {
        final ChunkDelta fromMemory = loadDeltaFromMemory(pos);
        if (fromMemory != null) return fromMemory;
        return loadDeltaFromDisk(pos, world);
    }

    /**
     * Returns a non-empty delta from the in-memory tracker, or null.
     *
     * @param pos the chunk position
     * @return the in-memory delta, or null if absent or empty
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDeltaFromMemory(final ChunkPos pos) {
        final ChunkDelta delta = GlobalChunkTracker.getDelta(pos);
        return (delta != null && !delta.isEmpty()) ? delta : null;
    }

    /**
     * Loads a delta from persistent CIS storage.
     *
     * @param pos   the chunk position
     * @param world the server world providing the storage instance
     * @return the loaded delta, or null if no entry exists on disk
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDeltaFromDisk(final ChunkPos pos, final ServerWorld world) {
        return FabricCisStorageHelper.getStorage(world).load(new CisChunkPos(pos.x, pos.z));
    }

    // -------------------------------------------------------------------------
    // Chunk mutation helpers
    // -------------------------------------------------------------------------

    /**
     * Attaches the given delta to the chunk via {@link ChunkisDeltaDuck}.
     * No-ops if the chunk does not implement the interface.
     *
     * @param chunk the proto chunk to attach to
     * @param delta the delta to attach
     */
    @Unique
    @SuppressWarnings({"rawtypes"})
    private static void attachDeltaToChunk(final ProtoChunk chunk, final ChunkDelta delta) {
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            deltaDuck.chunkis$setDelta(delta);
        }
    }

    /**
     * Resets the chunk's generation status to {@link ChunkStatus#EMPTY} so the
     * worldgen pipeline re-runs and applies the delta on top of fresh terrain.
     *
     * @param chunk the proto chunk to reset
     */
    @Unique
    private static void resetChunkStatus(final ProtoChunk chunk) {
        chunk.setStatus(ChunkStatus.EMPTY);
    }

    // -------------------------------------------------------------------------
    // Guard predicates
    // -------------------------------------------------------------------------

    /**
     * Returns true if the NBT compound contains the Chunkis marker key.
     *
     * @param nbt the chunk NBT to inspect
     * @return true if Chunkis data is present
     */
    @Unique
    private static boolean hasChunkisData(final NbtCompound nbt) {
        return nbt.contains(CisNbtUtil.CHUNKIS_DATA_KEY);
    }

    /**
     * Returns true if the given delta is null or contains no changes.
     *
     * @param delta the delta to test, may be null
     * @return true if the delta should be skipped
     */
    @Unique
    private static boolean isDeltaAbsent(final ChunkDelta<?, ?> delta) {
        return delta == null || delta.isEmpty();
    }
}