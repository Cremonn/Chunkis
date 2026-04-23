package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisConstants;
import io.liparakis.chunkis.util.ChunkRestorer;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import io.liparakis.chunkis.util.LeafTickContext;
import io.liparakis.chunkis.util.VanillaChunkSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link WorldChunk} that implements player modification tracking
 * and restoration.
 * <p>
 * This mixin provides two core functionalities:
 * <ol>
 * <li>Tracks block changes made by players/non-generation systems</li>
 * <li>Restores previously saved modifications when chunks are loaded</li>
 * </ol>
 * <p>
 * The tracking system uses {@link VanillaChunkSnapshot} to differentiate
 * between generated blocks and player modifications, ensuring only actual
 * changes are persisted to the delta.
 * <p>
 * <b>Note:</b> This mixin relies on {@link CommonChunkMixin} being applied
 * to the base {@link net.minecraft.world.chunk.Chunk} class for delta storage.
 *
 * @author Liparakis
 * @version 1.1
 */
@Mixin(WorldChunk.class)
public class WorldChunkMixin {

    /**
     * Cached predicate for nether portal POI lookups.
     */
    @Unique
    private static final java.util.function.Predicate<RegistryEntry<PointOfInterestType>> PORTAL_POI_PREDICATE = type -> type.matchesKey(PointOfInterestTypes.NETHER_PORTAL);

    @Unique
    private VanillaChunkSnapshot chunkis$vanillaSnapshot;

    @Unique
    private volatile boolean chunkis$isRestoring = false;

    // -----------------------------------------------------------------------
    // Mixin injection points
    // -----------------------------------------------------------------------

    /**
     * Intercepts block state changes to track player modifications.
     * <p>
     * This method filters out generation-time changes, natural decay, and
     * cross-thread modifications, capturing only intentional player edits
     * to the delta.
     * <p>
     * <b>Performance Note:</b> This is on the hot path (called for every block
     * change). Early exits minimize overhead for filtered cases.
     *
     * @param pos   the block position being modified
     * @param state the new block state
     * @param moved whether the block was moved
     * @param cir   callback containing the previous block state
     */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void chunkis$onSetBlockState(final BlockPos pos, final BlockState state, final boolean moved, final CallbackInfoReturnable<BlockState> cir) {

        if (!shouldTrackBlockChange(getWorldChunk(), state)) {
            return;
        }

        if (!state.hasBlockEntity()) {
            final int localX = pos.getX() & CisConstants.COORD_MASK;
            final int localY = pos.getY();
            final int localZ = pos.getZ() & CisConstants.COORD_MASK;
            @SuppressWarnings("unchecked") ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) getDelta();
            delta.removeBlockEntityData(localX, localY, localZ);
        }

        updateDeltaForBlockChange(pos, state);
    }

    /**
     * Intercepts block entity additions to proactively update the delta.
     */
    @SuppressWarnings("unchecked")
    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void chunkis$onSetBlockEntity(final BlockEntity blockEntity, final CallbackInfo ci) {
        if (!shouldTrackBlockChange(getWorldChunk(), getWorldChunk().getBlockState(blockEntity.getPos()))) {
            return;
        }
        if (getWorldChunk().getWorld() instanceof ServerWorld serverWorld) {
            try {
                io.liparakis.chunkis.util.ChunkBlockEntityCapture.captureBlockEntity(blockEntity, serverWorld.getRegistryManager(), (ChunkDelta<BlockState, NbtCompound>) getDelta());
                GlobalChunkTracker.markDirty(getWorldChunk());
            } catch (Exception e) {
                Chunkis.LOGGER.error("Chunkis: Failed to proactively capture added block entity at {}", blockEntity.getPos(), e);
            }
        }
    }

    /**
     * Intercepts block entity removals to proactively update the delta.
     */
    @SuppressWarnings("unchecked")
    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void chunkis$onRemoveBlockEntity(final BlockPos pos, final CallbackInfo ci) {
        if (!shouldTrackBlockChange(getWorldChunk(), getWorldChunk().getBlockState(pos))) {
            return;
        }
        final int localX = pos.getX() & CisConstants.COORD_MASK;
        final int localY = pos.getY();
        final int localZ = pos.getZ() & CisConstants.COORD_MASK;

        ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) getDelta();
        delta.removeBlockEntityData(localX, localY, localZ);
        GlobalChunkTracker.markDirty(getWorldChunk());
    }

    /**
     * Intercepts WorldChunk construction from ProtoChunk to restore saved
     * modifications.
     * <p>
     * When a chunk is promoted from ProtoChunk to WorldChunk (after generation
     * completes), this method:
     * <ol>
     * <li>Captures a snapshot of the vanilla-generated state</li>
     * <li>Restores block changes from the delta</li>
     * <li>Optimizes the delta by removing redundant entries</li>
     * </ol>
     * <p>
     * The restoration flag prevents the restored blocks from being re-tracked as
     * new changes.
     *
     * @param world        the server world
     * @param protoChunk   the ProtoChunk being promoted
     * @param entityLoader the entity loader for the chunk
     * @param ci           callback info
     */
    @Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;Lnet/minecraft/world/chunk/WorldChunk$EntityLoader;)V", at = @At("RETURN"))
    private void chunkis$onConstructFromProto(final ServerWorld world, final ProtoChunk protoChunk, final WorldChunk.EntityLoader entityLoader, final CallbackInfo ci) {

        chunkis$vanillaSnapshot = new VanillaChunkSnapshot(protoChunk);

        final ChunkDelta<BlockState, NbtCompound> protoDelta = resolveProtoDelta(protoChunk);
        if (protoDelta == null || protoDelta.isEmpty()) {
            return;
        }
        restoreChunkFromDelta(world, getWorldChunk(), protoChunk, protoDelta);
    }

    // -----------------------------------------------------------------------
    // Tracking guards
    // -----------------------------------------------------------------------

    /**
     * Determines if a block change should be tracked in the delta.
     * <p>
     * Block changes are ignored if:
     * <ul>
     * <li>The chunk is client-side</li>
     * <li>Restoration is in progress</li>
     * <li>The chunk is not fully generated</li>
     * <li>The change is from natural leaf decay</li>
     * <li>The change is from a different thread</li>
     * </ul>
     * <p>
     * A null vanilla snapshot no longer prevents tracking. If the snapshot
     * is missing (unexpected code path), the change is tracked without
     * vanilla deduplication to prevent silent data loss.
     *
     * @param chunk the chunk being modified
     * @param state the new block state
     * @return {@code true} if the change should be tracked
     */
    @Unique
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean shouldTrackBlockChange(final WorldChunk chunk, final BlockState state) {
        if (chunk.getWorld().isClient()) {
            return false;
        }
        if (chunkis$isRestoring) {
            return false;
        }
        if (!ChunkStatus.FULL.equals(chunk.getStatus())) {
            return false;
        }
        // Leaf decay is filtered before the snapshot-null warning so the warning
        // does not fire for changes that will be discarded anyway.
        if (isNaturalLeafDecay(state)) {
            return false;
        }
        if (chunkis$vanillaSnapshot == null) {
            Chunkis.LOGGER.debug("Chunkis: Tracking block change without vanilla snapshot for chunk {} " + "- deduplication disabled", chunk.getPos());
        }
        if (!isOnServerThread(chunk)) {
            Chunkis.LOGGER.warn("Chunkis: Block change rejected - not on server thread for chunk {} (thread: {})", chunk.getPos(), Thread.currentThread().getName());
            return false;
        }
        return true;
    }

    /**
     * Checks if a block change is from natural leaf decay.
     *
     * @param state the block state
     * @return {@code true} if this is natural leaf decay
     */
    @Unique
    private boolean isNaturalLeafDecay(final BlockState state) {
        return state.getBlock() instanceof LeavesBlock && LeafTickContext.isActive();
    }

    /**
     * Returns {@code true} if the current thread is the server thread for the
     * world owning this chunk.
     *
     * @param chunk the chunk whose world's server thread is checked
     * @return {@code true} if this call is on the server thread
     */
    @Unique
    private boolean isOnServerThread(final WorldChunk chunk) {
        if (!(chunk.getWorld() instanceof ServerWorld serverWorld)) {
            return false;
        }
        // Reference equality is intentional: we are comparing thread identity,
        // not thread names or logical equality.
        return serverWorld.getServer().getThread() == Thread.currentThread();
    }

    // -----------------------------------------------------------------------
    // Delta mutation helpers
    // -----------------------------------------------------------------------

    /**
     * Updates the delta to reflect a block change.
     * <p>
     * If a vanilla snapshot exists and the block matches vanilla state,
     * the delta entry is removed. Otherwise, the change is recorded.
     * <p>
     * When the vanilla snapshot is null (unexpected construction path),
     * the change is tracked unconditionally without deduplication to
     * prevent silent data loss.
     *
     * @param pos   the block position
     * @param state the new block state
     */
    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"}) // Raw ChunkDelta: getDelta() returns wildcard;
    // addBlockChange/removeBlockChange are type-erased
    private void updateDeltaForBlockChange(final BlockPos pos, final BlockState state) {
        final int localX = pos.getX() & CisConstants.COORD_MASK;
        final int localY = pos.getY();
        final int localZ = pos.getZ() & CisConstants.COORD_MASK;

        final ChunkDelta delta = getDelta();

        if (chunkis$vanillaSnapshot != null) {
            final BlockState vanillaState = chunkis$vanillaSnapshot.getVanillaState(localX, localY, localZ);
            if (isRevertedToVanilla(vanillaState, state)) {
                delta.removeBlockChange(localX, localY, localZ);
                return;
            }
        }

        delta.addBlockChange(localX, localY, localZ, state);
        GlobalChunkTracker.markDirty(getWorldChunk());
    }

    /**
     * Checks if a block has been reverted to its vanilla state.
     *
     * @param vanillaState the original vanilla state
     * @param currentState the current state
     * @return {@code true} if the block matches vanilla
     */
    @Unique
    private boolean isRevertedToVanilla(final BlockState vanillaState, final BlockState currentState) {
        return vanillaState != null && vanillaState.equals(currentState);
    }

    // -----------------------------------------------------------------------
    // Restoration helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the typed {@link ChunkDelta} from a {@link ProtoChunk}'s
     * {@link ChunkisDeltaDuck} interface.
     *
     * <p>
     * Returns {@code null} if the proto does not implement
     * {@link ChunkisDeltaDuck} or if the delta itself is {@code null}.
     *
     * @param proto the ProtoChunk to resolve the delta from
     * @return the typed delta, or {@code null} if unavailable
     */
    @Unique
    @SuppressWarnings("unchecked") // Safe: chunkis$getDelta returns our own typed delta
    private ChunkDelta<BlockState, NbtCompound> resolveProtoDelta(final ProtoChunk proto) {
        if (!(proto instanceof ChunkisDeltaDuck deltaDuck)) {
            return null;
        }
        return (ChunkDelta<BlockState, NbtCompound>) deltaDuck.chunkis$getDelta();
    }

    /**
     * Restores chunk modifications from a delta.
     * <p>
     * Sets the restoration flag to prevent re-tracking of restored blocks,
     * then delegates to {@link ChunkRestorer} for the actual restoration logic.
     * <p>
     * If optimization occurs during restoration, the delta is marked dirty
     * to ensure it's re-saved with redundant entries removed.
     *
     * @param world      the server world
     * @param chunk      the chunk being restored
     * @param proto      the ProtoChunk source
     * @param protoDelta the delta to restore from
     */
    @Unique
    @SuppressWarnings("unchecked") // Safe: getDelta returns our own typed delta
    private void restoreChunkFromDelta(final ServerWorld world, final WorldChunk chunk, final ProtoChunk proto, final ChunkDelta<BlockState, NbtCompound> protoDelta) {

        final ChunkDelta<BlockState, NbtCompound> selfDelta = (ChunkDelta<BlockState, NbtCompound>) getDelta();
        selfDelta.setSuppressInitialRepopulation(protoDelta.shouldSuppressInitialRepopulation());

        try {
            chunkis$isRestoring = true;

            final boolean wasOptimized = ChunkRestorer.restore(world, chunk, protoDelta, selfDelta, chunkis$vanillaSnapshot);

            resyncPortalPointOfInterestStorage(world, chunk);

            if (wasOptimized) {
                selfDelta.markDirty();
            }

            GlobalChunkTracker.markDirty(chunk);

        } catch (final Exception e) {
            Chunkis.LOGGER.error("Chunkis: Failed to restore chunk {}", proto.getPos(), e);
        } finally {
            chunkis$isRestoring = false;
        }

        protoDelta.markSaved();
    }

    // -----------------------------------------------------------------------
    // Self-cast helpers
    // -----------------------------------------------------------------------

    /**
     * Retrieves the delta for this chunk by casting {@code this} to
     * {@link ChunkisDeltaDuck}.
     *
     * <p>
     * This is safe because {@link CommonChunkMixin} is applied to the base
     * {@link net.minecraft.world.chunk.Chunk} class, guaranteeing all
     * {@link WorldChunk} instances implement {@link ChunkisDeltaDuck}.
     *
     * @return the chunk delta
     */
    @Unique
    private ChunkDelta<?, ?> getDelta() {
        return ((ChunkisDeltaDuck) this).chunkis$getDelta();
    }

    /**
     * Casts this mixin instance to {@link WorldChunk}.
     *
     * <p>
     * This is the standard Mixin self-cast pattern and is safe because this
     * mixin targets {@link WorldChunk} exclusively.
     *
     * @return this instance as {@link WorldChunk}
     */
    @Unique
    private WorldChunk getWorldChunk() {
        return (WorldChunk) (Object) this;
    }

    /**
     * Rebuilds vanilla portal POI data for restored chunks that contain nether
     * portal blocks.
     *
     * <p>Chunkis restores block changes after vanilla deserialization has already
     * initialized POIs. Nether portal lookup reads the POI index, not just block
     * states, so restored portal blocks need a local POI rescan or vanilla may
     * create a duplicate destination portal.</p>
     *
     * <p>This calls {@link PointOfInterestStorage#add} once per restored portal
     * block rather than {@code initForPalette}. Existing POI sections route
     * through {@code PointOfInterestSet.updatePointsOfInterest}, which only
     * rebuilds when the set is invalid. The direct add path delegates to a set
     * insertion that returns {@code false} for already-registered positions, so
     * missing POIs are repaired without accumulating duplicates.</p>
     *
     * <p>This constructor path runs on the server thread during Chunkis'
     * synchronous chunk restoration. Do not add external synchronization around
     * {@link PointOfInterestStorage}; vanilla does not synchronize on that monitor,
     * so doing so would only create false confidence rather than real safety.</p>
     *
     * @param world the world owning the restored chunk
     * @param chunk the restored chunk to inspect
     */
    @Unique
    private void resyncPortalPointOfInterestStorage(final ServerWorld world, final WorldChunk chunk) {
        final int portalBlockCount = countPortalBlocks(chunk);
        if (portalBlockCount == 0) return;


        final PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        final RegistryEntry<PointOfInterestType> portalPoiType =
                world.getRegistryManager().get(RegistryKeys.POINT_OF_INTEREST_TYPE).entryOf(PointOfInterestTypes.NETHER_PORTAL);

        addPortalPois(chunk, poiStorage, portalPoiType);

        final long portalPoiCount = poiStorage.getInChunk(PORTAL_POI_PREDICATE, chunk.getPos(), PointOfInterestStorage.OccupationStatus.ANY).count();

        if (portalPoiCount == 0) {
            Chunkis.LOGGER.warn(
                    "Chunkis [PORTAL]: Restored chunk {} in {} has {} portal block(s) but no portal POIs after resync",
                    chunk.getPos(),
                    world.getRegistryKey().getValue(),
                    portalBlockCount
            );
        } else if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug(
                    "Chunkis [PORTAL]: Restored chunk {} in {} with {} portal block(s) and {} portal POI(s)",
                    chunk.getPos(),
                    world.getRegistryKey().getValue(),
                    portalBlockCount,
                    portalPoiCount
            );
        }
    }

    /**
     * Counts nether portal blocks in a chunk.
     *
     * @param chunk the chunk to scan
     * @return number of nether portal blocks; used to gate and diagnose POI repair
     */
    @Unique
    private int countPortalBlocks(final WorldChunk chunk) {
        final int[] count = new int[1];
        chunk.forEachBlockMatchingPredicate(state -> state.isOf(Blocks.NETHER_PORTAL), (pos, state) -> count[0]++);
        return count[0];
    }

    /**
     * Registers a nether portal POI for each portal block in the chunk.
     *
     * @param chunk         the chunk to scan
     * @param poiStorage    POI storage for the owning world
     * @param portalPoiType registry entry for nether portal POIs
     */
    @Unique
    private void addPortalPois(final WorldChunk chunk, final PointOfInterestStorage poiStorage, final RegistryEntry<PointOfInterestType> portalPoiType) {
        chunk.forEachBlockMatchingPredicate(state -> state.isOf(Blocks.NETHER_PORTAL), (pos, state) -> poiStorage.add(pos, portalPoiType));
    }
}
