package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.util.CisNbtUtil;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Intercepts chunk load and save operations in
 * {@link ServerChunkLoadingManager},
 * delegating persistence to the Chunkis CIS delta system instead of vanilla MCA
 * files.
 *
 * <p>
 * <b>Load path</b> ({@link #chunkis$onGetUpdatedChunkNbt}): Provides synthetic
 * NBT
 * constructed from the CIS delta so downstream deserialization (including
 * {@code ChunkSerializerMixin}) can attach the delta to the resulting
 * {@link net.minecraft.world.chunk.ProtoChunk}.
 *
 * <p>
 * <b>Save path</b> ({@link #chunkis$onSave}): Captures live block-entities and
 * entities into the delta, then flushes dirty deltas to CIS storage.
 *
 * <p>
 * <b>Shutdown</b> ({@link #chunkis$onClose}): Force-saves any deltas still
 * marked
 * dirty after the final tick, then closes CIS storage.
 *
 * <p>
 * <b>Thread safety:</b> All injected methods execute on the server thread.
 *
 * @author Liparakis
 * @version 2.1
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ThreadedAnvilChunkStorageMixin {

    @Shadow
    @Final
    ServerWorld world;

    /** Cached game data version — same for the lifetime of the server process. */
    @Unique
    private static final int GAME_DATA_VERSION = net.minecraft.SharedConstants.getGameVersion().getSaveVersion()
            .getId();

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Ensures CIS storage is properly closed during server shutdown.
     *
     * <p>
     * Injected at {@code TAIL} so Minecraft's internal {@code tick(() -> true)}
     * completes all pending saves while CIS storage is still open. A pre-close
     * sweep force-saves any deltas still marked dirty as a safety net for chunks
     * that were not flushed during the final tick.
     *
     * @param ci mixin callback (not cancelled)
     */
    @Inject(method = "close", at = @At("TAIL"))
    private void chunkis$onClose(final CallbackInfo ci) {
        forceSaveRemainingDeltas();
        FabricCisStorageHelper.closeStorage(world);
    }

    /**
     * Force-saves all dirty deltas still present in the tracker.
     * Logs a warning with the count so operators know shutdown needed extra work.
     */
    @SuppressWarnings("unchecked")
    @Unique
    private void forceSaveRemainingDeltas() {
        final var pending = GlobalChunkTracker.getPendingPositions();
        if (pending.isEmpty())
            return;

        Chunkis.LOGGER.warn("Chunkis [CLOSE]: Force-saving {} remaining dirty delta(s) for {}",
                pending.size(), world.getRegistryKey().getValue());

        for (final ChunkPos pos : pending) {
            final ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker
                    .getDelta(pos);

            if (delta != null && delta.isDirty()) {
                persistDelta(pos, delta);
                GlobalChunkTracker.markSaved(pos);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Load path
    // -------------------------------------------------------------------------

    /**
     * Intercepts the chunk NBT load to provide CIS-backed data instead of reading
     * from vanilla {@code .mca} files.
     *
     * <p>
     * If a dirty delta exists in the tracker it is flushed to disk first, ensuring
     * the synthetic NBT reflects the latest state. If no in-memory delta exists, a
     * cold load from CIS storage is attempted. The result is wrapped in a minimal
     * base NBT compound (via {@link CisNbtUtil}) and returned immediately —
     * cancelling the vanilla I/O path entirely.
     *
     * @param pos the chunk position to load
     * @param cir callback whose return value is set to a completed future
     */
    @SuppressWarnings("unchecked")
    @Inject(method = "getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void chunkis$onGetUpdatedChunkNbt(
            final ChunkPos pos,
            final CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {

        final CisChunkPos cisPos = toCisChunkPos(pos);

        ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker
                .getDelta(pos);

        if (delta != null) {
            if (delta.isDirty()) {
                getStorage().save(cisPos, delta);
                GlobalChunkTracker.markSaved(pos);
            }
        } else {
            delta = getStorage().load(cisPos);
        }

        cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(buildChunkNbt(pos, delta))));
    }

    // -------------------------------------------------------------------------
    // Save path
    // -------------------------------------------------------------------------

    /**
     * Intercepts the per-chunk save to capture live data and flush dirty deltas
     * to CIS storage instead of vanilla {@code .mca} files.
     *
     * <p>
     * Delta resolution order:
     * <ol>
     * <li>Global tracker — has the widest availability and reflects in-flight
     * modifications.</li>
     * <li>Chunk's own duck interface — fallback for chunks not yet registered in
     * the tracker.</li>
     * </ol>
     * If neither source yields a delta the save is a no-op (returns {@code true}
     * to prevent vanilla from attempting an {@code .mca} write, which
     * {@code StoragePreventionMixin} would discard anyway).
     *
     * @param chunkHolder the holder containing the chunk to save
     * @param cir         callback whose return value is set to true
     */
    @Inject(method = "save(Lnet/minecraft/server/world/ChunkHolder;)Z", at = @At("HEAD"), cancellable = true)
    private void chunkis$onSave(
            final ChunkHolder chunkHolder,
            final CallbackInfoReturnable<Boolean> cir) {

        final ChunkPos pos = chunkHolder.getPos();
        final Chunk chunk = selectChunkForSaving(chunkHolder);

        ChunkDelta<BlockState, NbtCompound> delta = resolveTrackerDelta(pos);

        if (delta == null) {
            delta = resolveChunkDelta(chunk);
        }

        if (delta == null) {
            // No modifications — cancel vanilla save (StoragePreventionMixin would
            // discard any .mca write regardless).
            cir.setReturnValue(true);
            return;
        }

        // No longer capturing bulk chunk data here because Chunkis is now proactive.

        if (delta.isDirty()) {
            persistDelta(pos, delta);
            GlobalChunkTracker.markSaved(pos);
        }

        if (chunk != null) {
            chunk.setNeedsSaving(false);
        }

        cir.setReturnValue(true);
    }

    // -------------------------------------------------------------------------
    // Delta resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the delta from the global tracker for the given position, or null.
     *
     * @param pos the chunk position
     * @return the tracked delta, or null if absent
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> resolveTrackerDelta(final ChunkPos pos) {
        return (ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker.getDelta(pos);
    }

    /**
     * Returns the delta attached to the chunk via {@link ChunkisDeltaDuck}, or null
     * if the chunk is null or does not implement the interface.
     *
     * @param chunk the chunk to inspect, may be null
     * @return the chunk's own delta, or null
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> resolveChunkDelta(final Chunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck))
            return null;
        return (ChunkDelta<BlockState, NbtCompound>) deltaDuck.chunkis$getDelta();
    }

    // -------------------------------------------------------------------------
    // Chunk selection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the most appropriate chunk instance for saving, preferring the live
     * world chunk over the async saving future.
     *
     * @param holder the chunk holder to inspect
     * @return the best available chunk instance, or null
     */
    @Unique
    private Chunk selectChunkForSaving(final ChunkHolder holder) {
        final Chunk live = holder.getWorldChunk();
        if (live != null)
            return live;
        return extractChunkFromSavingFuture(holder);
    }

    /**
     * Extracts a chunk from the holder's saving future if the future has completed.
     *
     * @param holder the chunk holder
     * @return the chunk from the future, or null if not yet available
     */
    @Unique
    private static Chunk extractChunkFromSavingFuture(final ChunkHolder holder) {
        final Object result = holder.getSavingFuture().getNow(null);
        if (result instanceof Optional<?> optional) {
            return (Chunk) optional.orElse(null);
        }
        return null;
    }

    // Method captureChunkData and captureBlockEntitiesIfLiveChunk removed because
    // we are using proactive capture now.

    // -------------------------------------------------------------------------
    // Persistence and conversion helpers
    // -------------------------------------------------------------------------

    /**
     * Flushes a delta to CIS storage and logs a debug entry.
     *
     * @param pos   the chunk position
     * @param delta the delta to save
     */
    @Unique
    private void persistDelta(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta) {

        getStorage().save(toCisChunkPos(pos), delta);
        Chunkis.LOGGER.debug("Chunkis: Saved CIS chunk {}", pos);
    }

    /**
     * Builds the minimal NBT compound used to ferry a delta through the vanilla
     * chunk deserialization pipeline.
     *
     * @param pos   the chunk position
     * @param delta the delta to embed, may be null
     * @return the populated NBT compound
     */
    @Unique
    private static NbtCompound buildChunkNbt(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta) {

        final NbtCompound nbt = CisNbtUtil.createBaseNbt(pos, GAME_DATA_VERSION);
        CisNbtUtil.putDelta(nbt, delta);
        return nbt;
    }

    /**
     * Converts a Minecraft {@link ChunkPos} to a {@link CisChunkPos}.
     *
     * @param pos the Minecraft chunk position
     * @return the equivalent CIS position
     */
    @Unique
    private static CisChunkPos toCisChunkPos(final ChunkPos pos) {
        return new CisChunkPos(pos.x, pos.z);
    }

    /**
     * Returns the CIS storage instance for this world.
     * All access is on the server thread, so no additional synchronization is
     * needed.
     *
     * @return the active CIS storage instance
     */
    @Unique
    private CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage() {
        return FabricCisStorageHelper.getStorage(world);
    }
}