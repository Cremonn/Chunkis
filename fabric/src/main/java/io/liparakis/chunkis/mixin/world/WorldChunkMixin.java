package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.portal.PortalChunkIndexManager;
import io.liparakis.chunkis.storage.BaseChunkCaptureUtil;
import io.liparakis.chunkis.storage.CisNbtUtil;
import io.liparakis.chunkis.storage.model.CisConstants;
import io.liparakis.chunkis.world.ChunkBlockEntityCapture;
import io.liparakis.chunkis.world.ChunkRestorer;
import io.liparakis.chunkis.world.GlobalChunkTracker;
import io.liparakis.chunkis.world.LeafTickContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Mixin for {@link WorldChunk} implementing Chunkis delta tracking and restoration.
 *
 * <h3>Tracking</h3>
 * <p>Block and block-entity changes made after chunk generation are recorded into
 * a sparse {@link ChunkDelta}. To keep the delta genuinely sparse, the vanilla/
 * generated block state at each edited position is captured lazily on first edit
 * (before vanilla mutates the chunk). When a player reverts a block to that
 * captured baseline the delta entry is removed rather than persisted forever.</p>
 *
 * <h3>Restoration</h3>
 * <p>When a {@link ProtoChunk} is promoted to a {@link WorldChunk}, saved sparse
 * edits are replayed on top of the freshly generated vanilla chunk via
 * {@link ChunkRestorer}.</p>
 *
 * <h3>Hot-path note</h3>
 * <p>{@code setBlockState} is called extremely often. All guard checks must be
 * cheap, allocation-free, and avoid unnecessary world/chunk lookups.</p>
 *
 * <p><b>Mixin dependency:</b> relies on {@code CommonChunkMixin} applying
 * {@link ChunkisDeltaDuck} to the base chunk type.</p>
 *
 * @author Liparakis
 * @version 1.2
 *
 */
@Mixin(WorldChunk.class)
public class WorldChunkMixin {


    /**
     * Reusable predicate for nether portal POI queries.
     * Stored statically to avoid a new lambda allocation per resync call.
     */
    @Unique
    private static final Predicate<RegistryEntry<PointOfInterestType>> PORTAL_POI_PREDICATE =
            type -> type.matchesKey(PointOfInterestTypes.NETHER_PORTAL);

    /**
     * Reusable predicate for scanning nether portal blocks inside a chunk during
     * post-restore POI repair.
     */
    @Unique
    private static final Predicate<BlockState> NETHER_PORTAL_BLOCK_PREDICATE =
            state -> state.isOf(Blocks.NETHER_PORTAL);

    /**
     * Lazily captured vanilla/generated baseline states, keyed by packed local
     * chunk position.
     *
     * <p>A baseline is captured only when a block is actually edited. Because the
     * injection runs at {@code HEAD} of {@code setBlockState}, the chunk still
     * holds the pre-change state — exactly the baseline we need.</p>
     */
    @Unique
    private final Long2ObjectMap<BlockState> chunkis$vanillaBaselines = new Long2ObjectOpenHashMap<>();

    /**
     * {@code true} while Chunkis is replaying saved block changes during restoration.
     * Suppresses re-tracking of the restored writes as fresh player edits.
     */
    @Unique
    private volatile boolean chunkis$isRestoring;

    /**
     * Cached server thread for the owning world.
     *
     * <p>Chunk mutations from other threads are rejected. Cached after first lookup
     * via reference equality — thread identity, not name, is what matters.</p>
     */
    @Unique
    private Thread chunkis$serverThread;

    /**
     * Temporary counter used during portal POI resync, stored on the instance to
     * avoid allocating a single-element {@code int[]} per resync call.
     */
    @Unique
    private int chunkis$portalBlockCount;

    /**
     * Intercepts block state writes at {@code HEAD} (before vanilla mutates the
     * chunk) to record meaningful post-generation changes into the delta.
     *
     * <p>Ignored cases:</p>
     * <ul>
     *   <li>client-side chunks</li>
     *   <li>Chunkis restoration writes ({@link #chunkis$isRestoring})</li>
     *   <li>non-full/non-generated chunks</li>
     *   <li>cross-thread writes</li>
     *   <li>natural leaf decay</li>
     *   <li>no-op writes where previous and new state are the same reference</li>
     * </ul>
     *
     * @param pos   the block position being modified
     * @param state the new block state
     * @param flags vanilla block update flags
     * @param cir   the callback info
     */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void chunkis$onSetBlockState(
            final BlockPos pos, final BlockState state, final int flags,
            final CallbackInfoReturnable<BlockState> cir) {
        final WorldChunk chunk = chunkis$self();
        if (chunkis$shouldNotTrackChunkMutation(chunk)) {
            return;
        }

        final BlockState previous = chunk.getBlockState(pos);
        // Vanilla BlockStates are commonly canonicalized - reference equality is the
        // cheapest no-op check before paying for a full property comparison.
        if (previous == state || chunkis$isNaturalLeafDecay(previous, state)) {
            return;
        }

        final int localX = pos.getX() & CisConstants.COORD_MASK;
        final int localY = pos.getY();
        final int localZ = pos.getZ() & CisConstants.COORD_MASK;

        final ChunkDelta<BlockState, NbtCompound> delta = chunkis$getBlockDelta();

        chunkis$captureBaseChunkBeforeMutation(chunk, delta);

        // If the new state cannot own a block entity, remove any stale BE payload.
        if (!state.hasBlockEntity()) {
            delta.removeBlockEntityData(localX, localY, localZ);
        }

        chunkis$updateDeltaForBlockChange(chunk, delta, previous, state, localX, localY, localZ);
    }

    /**
     * Refreshes the portal chunk index after a portal block is created or removed.
     *
     * <p>Runs at {@code RETURN} so the index sees the post-mutation block grid,
     * not the pre-change state observed by the {@code HEAD} hook.</p>
     *
     * @param pos   the block position that changed
     * @param state the new block state
     * @param flags vanilla block update flags
     * @param cir   the callback info
     */
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void chunkis$afterSetBlockState(
            final BlockPos pos, final BlockState state, final int flags,
            final CallbackInfoReturnable<BlockState> cir) {
        final WorldChunk chunk = chunkis$self();
        if (chunkis$shouldNotTrackChunkMutation(chunk)) {
            return;
        }

        final BlockState previous = cir.getReturnValue();
        final boolean portalChanged =
                (previous != null && previous.isOf(Blocks.NETHER_PORTAL)) || state.isOf(Blocks.NETHER_PORTAL);

        if (portalChanged && chunk.getWorld() instanceof ServerWorld serverWorld) {
            PortalChunkIndexManager.updateChunk(serverWorld, chunk);
        }
    }

    /**
     * Captures newly added or replaced block entities into the delta.
     *
     * <p>Block state changes are handled by {@link #chunkis$onSetBlockState}. This
     * hook covers block entity NBT that is attached or replaced after the block
     * state is already present.</p>
     *
     * @param blockEntity the block entity being installed into the chunk
     * @param ci          the callback info
     */
    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void chunkis$onSetBlockEntity(final BlockEntity blockEntity, final CallbackInfo ci) {
        final WorldChunk chunk = chunkis$self();
        if (chunkis$shouldNotTrackChunkMutation(chunk)) {
            return;
        }

        final BlockPos pos = blockEntity.getPos();
        if (!chunk.getBlockState(pos).hasBlockEntity()) {
            return;
        }
        if (!(chunk.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta = chunkis$getMutationDeltaWithBaseCapture(chunk);

        try {
            ChunkBlockEntityCapture.captureBlockEntity(blockEntity, serverWorld.getRegistryManager(), delta);
            GlobalChunkTracker.markDirty(chunk);
        } catch (final Exception e) {
            Chunkis.LOGGER.error("Chunkis: Failed to capture block entity at {}", pos, e);
        }
    }

    /**
     * Removes block entity NBT from the delta when vanilla removes a block entity,
     * keeping the sparse payload aligned with the current block state.
     *
     * @param pos the removed block entity position
     * @param ci  the callback info
     */
    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void chunkis$onRemoveBlockEntity(final BlockPos pos, final CallbackInfo ci) {
        final WorldChunk chunk = chunkis$self();
        if (chunkis$shouldNotTrackChunkMutation(chunk)) {
            return;
        }

        final int localX = pos.getX() & CisConstants.COORD_MASK;
        final int localY = pos.getY();
        final int localZ = pos.getZ() & CisConstants.COORD_MASK;

        final ChunkDelta<BlockState, NbtCompound> delta = chunkis$getMutationDeltaWithBaseCapture(chunk);

        delta.removeBlockEntityData(localX, localY, localZ);
        GlobalChunkTracker.markDirty(chunk);
    }

    /**
     * Restores Chunkis delta data when a generated {@link ProtoChunk} is promoted
     * to a {@link WorldChunk}.
     *
     * <p>This constructor path is where Minecraft promotes a completed generated
     * chunk into its runtime representation. Chunkis uses that moment to apply
     * saved sparse edits on top of the freshly generated vanilla chunk.</p>
     *
     * @param world        the server world
     * @param protoChunk   the source proto chunk
     * @param entityLoader vanilla entity loader
     * @param ci           the callback info
     */
    @Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;" +
            "Lnet/minecraft/world/chunk/WorldChunk$EntityLoader;)V", at = @At("RETURN"))
    private void chunkis$onConstructFromProto(
            final ServerWorld world, final ProtoChunk protoChunk,
            final WorldChunk.EntityLoader entityLoader, final CallbackInfo ci) {
        final ChunkDelta<BlockState, NbtCompound> protoDelta = chunkis$resolveProtoDelta(protoChunk);
        if (protoDelta == null || protoDelta.isEmpty()) {
            return;
        }
        chunkis$restoreChunkFromDelta(world, chunkis$self(), protoChunk, protoDelta);
    }

    /**
     * Returns {@code true} when a chunk mutation must not enter Chunkis tracking.
     *
     * <p>Only chunk/world-level conditions are checked here. State-specific filters
     * (e.g. natural leaf decay) are applied separately once the previous state is
     * known.</p>
     *
     * @param chunk the chunk being mutated
     * @return {@code true} if the mutation should be ignored
     */
    @Unique
    private boolean chunkis$shouldNotTrackChunkMutation(final WorldChunk chunk) {
        if (chunkis$isRestoring) {
            return true;
        }
        final World world = chunk.getWorld();
        if (world.isClient() || !ChunkStatus.FULL.equals(chunk.getStatus())) {
            return true;
        }
        if (!chunkis$isOnServerThread(world)) {
            Chunkis.LOGGER.warn(
                    "Chunkis: Block change rejected outside server thread for chunk {} on thread {}",
                    chunk.getPos(), Thread.currentThread().getName()
            );
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if this mutation looks like natural leaf decay and
     * should not be persisted as a player edit.
     *
     * <p>Decay often writes {@code AIR}, so checking only the new state can miss
     * it. Both states are checked while the {@link LeafTickContext} marker is
     * active.</p>
     *
     * @param previous the state currently stored in the chunk
     * @param next     the state vanilla is trying to write
     * @return {@code true} if this is natural leaf decay
     */
    @Unique
    private static boolean chunkis$isNaturalLeafDecay(final BlockState previous, final BlockState next) {
        return LeafTickContext.isActive() && (previous.getBlock() instanceof LeavesBlock || next.getBlock() instanceof LeavesBlock);
    }

    /**
     * Returns {@code true} when the current call is executing on the owning
     * Minecraft server thread.
     *
     * <p>The thread reference is cached after first lookup. Reference equality is
     * intentional — thread identity, not name, is what matters.</p>
     *
     * @param world the world owning the chunk
     * @return {@code true} when running on the server thread
     */
    @Unique
    private boolean chunkis$isOnServerThread(final World world) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }
        if (chunkis$serverThread == null) {
            final var server = serverWorld.getServer();
            if (server == null) {
                return false;
            }
            chunkis$serverThread = server.getThread();
        }
        return chunkis$serverThread == Thread.currentThread();
    }

    /**
     * Records or removes a block change in the sparse delta.
     *
     * <p>If the new state matches the captured vanilla/generated baseline, the
     * delta entry is removed (revert-to-vanilla). Otherwise it is stored as an
     * explicit sparse edit. Both paths finish with a dirty-mark on the tracker.</p>
     *
     * @param chunk    the owning chunk
     * @param delta    the chunk delta
     * @param previous the state before the write
     * @param next     the state being written
     * @param localX   local chunk X coordinate
     * @param localY   block Y coordinate
     * @param localZ   local chunk Z coordinate
     */
    @Unique
    private void chunkis$updateDeltaForBlockChange(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final BlockState previous, final BlockState next, final int localX
            , final int localY, final int localZ) {
        final long packed = BlockInstruction.packPos(localX, localY, localZ);
        final BlockState baseline = chunkis$getOrCaptureVanillaBaseline(packed, previous);

        if (chunkis$isRevertedToVanilla(baseline, next)) {
            chunkis$handleVanillaRevert(delta, packed, localX, localY, localZ, next);
        } else {
            delta.addBlockChange(localX, localY, localZ, next);
        }

        GlobalChunkTracker.markDirty(chunk);
    }

    /**
     * Triggers a synchronous base chunk capture before the first tracked live
     * mutation, while the chunk still reflects its pre-save in-memory state.
     *
     * <p>This prevents later load-path reconstruction from depending on a save
     * hook winning a race against chunk reload.</p>
     *
     * @param chunk the live chunk being mutated
     * @param delta the attached Chunkis delta
     */
    @Unique
    private void chunkis$captureBaseChunkBeforeMutation(
            final WorldChunk chunk, final ChunkDelta<BlockState,
                    NbtCompound> delta) {
        if (chunk.getWorld() instanceof ServerWorld serverWorld) {
            BaseChunkCaptureUtil.captureAndPersistBaseChunkIfMissing(serverWorld, chunk, delta);
        }
    }

    /**
     * Handles a block being changed back to its captured vanilla/generated state.
     *
     * <p>When the delta has a full block baseline the reverted state must still be
     * stored explicitly because the baseline itself has meaning. Otherwise the
     * sparse edit is removed and the cached baseline is evicted.</p>
     *
     * @param delta  the chunk delta
     * @param packed packed local block position
     * @param localX local chunk X coordinate
     * @param localY block Y coordinate
     * @param localZ local chunk Z coordinate
     * @param state  the reverted block state
     */
    @Unique
    private void chunkis$handleVanillaRevert(
            final ChunkDelta<BlockState, NbtCompound> delta, final long packed,
            final int localX, final int localY, final int localZ,
            final BlockState state) {
        if (CisNbtUtil.hasFullBlockBaseline(delta.getChunkMetadata())) {
            delta.addBlockChange(localX, localY, localZ, state);
        } else {
            delta.removeBlockChange(localX, localY, localZ);
            chunkis$vanillaBaselines.remove(packed);
        }
    }

    /**
     * Returns the previously captured vanilla baseline for {@code packed}, or
     * captures {@code current} as the new baseline on first access.
     *
     * <p>Because this is called before vanilla mutates the block, {@code current}
     * still represents the generated/pre-edit value for first-time edits.</p>
     *
     * @param packed  packed local block position
     * @param current block state before mutation
     * @return the captured vanilla/generated baseline state
     */
    @Unique
    private BlockState chunkis$getOrCaptureVanillaBaseline(final long packed, final BlockState current) {
        final BlockState existing = chunkis$vanillaBaselines.get(packed);
        if (existing != null) {
            return existing;
        }
        chunkis$vanillaBaselines.put(packed, current);
        return current;
    }

    /**
     * Returns {@code true} if {@code current} matches the captured baseline.
     *
     * <p>Reference equality is checked first (cheap, common for canonicalized
     * vanilla states); {@link BlockState#equals} is the safe fallback.</p>
     *
     * @param baseline the captured vanilla/generated state
     * @param current  the current/new block state
     * @return {@code true} if the block has been reverted to vanilla
     */
    @Unique
    private static boolean chunkis$isRevertedToVanilla(final BlockState baseline, final BlockState current) {
        return baseline == current || baseline.equals(current);
    }

    /**
     * Resolves the typed Chunkis delta from a proto chunk via
     * {@link ChunkisDeltaDuck}.
     *
     * @param proto the proto chunk to inspect
     * @return the proto chunk delta, or {@code null} if unavailable
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> chunkis$resolveProtoDelta(final ProtoChunk proto) {
        return proto instanceof ChunkisDeltaDuck duck ?
                (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta() : null;
    }

    /**
     * Replays saved Chunkis modifications onto a promoted world chunk.
     *
     * <p>The {@link #chunkis$isRestoring} flag suppresses tracking while saved edits
     * are being replayed. After restoration, portal POIs are repaired because vanilla
     * initializes POI data before Chunkis applies restored portal blocks.</p>
     *
     * @param world      the owning server world
     * @param chunk      the promoted world chunk
     * @param proto      the source proto chunk
     * @param protoDelta the saved delta from the proto chunk
     */
    @Unique
    private void chunkis$restoreChunkFromDelta(
            final ServerWorld world, final WorldChunk chunk,
            final ProtoChunk proto,
            final ChunkDelta<BlockState, NbtCompound> protoDelta) {
        final ChunkDelta<BlockState, NbtCompound> selfDelta = chunkis$getBlockDelta();

        selfDelta.setSuppressInitialRepopulation(protoDelta.shouldSuppressInitialRepopulation());
        selfDelta.setChunkMetadata(protoDelta.getChunkMetadata(), false);

        final boolean missingBase = !CisNbtUtil.hasPersistedBaseChunkNbt(protoDelta.getChunkMetadata());


        try {
            chunkis$isRestoring = true;

            final boolean wasOptimized = ChunkRestorer.restore(
                    world, chunk, protoDelta, selfDelta,
                    chunkis$vanillaBaselines
            );

            chunkis$resyncPortalPointOfInterestStorage(world, chunk);
            PortalChunkIndexManager.updateChunk(world, chunk);

            if (missingBase) {
                Chunkis.LOGGER.warn(
                        "Chunkis [RESTORE]: Restored chunk {} in {} from delta without persisted base. " +
                                "deltaEmptyBeforeCapture={} deltaDirtyBeforeCapture={} suppressInitialRepopulation={}",
                        chunk.getPos(), world.getRegistryKey().getValue(), selfDelta.isEmpty(),
                        selfDelta.isDirty(), selfDelta.shouldSuppressInitialRepopulation()
                );
                // Capture immediately after restore while the live chunk still represents
                // the fully reconstructed state. Waiting for a later save is racy: an
                // unload/reload can ask the IO thread for NBT before that save runs.
                chunkis$captureBaseChunkBeforeMutation(chunk, selfDelta);
            }

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

    /**
     * Rebuilds missing nether portal POI entries for restored portal blocks.
     *
     * <p>Vanilla portal lookup uses the POI index, not only block states. Chunkis
     * restores blocks after vanilla has already initialized chunk POIs, so restored
     * portal blocks can be invisible to portal search and cause duplicate destination
     * portals. This method repairs that by calling {@link PointOfInterestStorage#add}
     * for each restored portal block; duplicates are ignored by the underlying POI
     * set.</p>
     *
     * @param world the owning server world
     * @param chunk the restored chunk
     */
    @Unique
    private void chunkis$resyncPortalPointOfInterestStorage(final ServerWorld world, final WorldChunk chunk) {
        final PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        final RegistryEntry<PointOfInterestType> portalType =
                world.getRegistryManager().getOrThrow(RegistryKeys.POINT_OF_INTEREST_TYPE).getOrThrow(PointOfInterestTypes.NETHER_PORTAL);

        final int blockCount = chunkis$addPortalPois(chunk, poiStorage, portalType);
        if (blockCount == 0) {
            return;
        }

        final long poiCount = poiStorage.getInChunk(
                PORTAL_POI_PREDICATE, chunk.getPos(),
                PointOfInterestStorage.OccupationStatus.ANY
        ).count();

        if (poiCount == 0) {
            Chunkis.LOGGER.warn(
                    "Chunkis [PORTAL]: Restored chunk {} in {} has {} portal block(s) " + "but no portal " +
                            "POIs after resync", chunk.getPos(), world.getRegistryKey().getValue(), blockCount
            );
        } else if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug(
                    "Chunkis [PORTAL]: Restored chunk {} in {} with {} portal block(s) " + "and {} " +
                            "portal POI(s)", chunk.getPos(), world.getRegistryKey().getValue(), blockCount, poiCount
            );
        }
    }

    /**
     * Registers nether portal POIs for all portal blocks found in the chunk and
     * returns the count.
     *
     * <p>The counter is stored on the mixin instance (not a single-element array)
     * to avoid a small allocation per resync call. This method resets the counter
     * before and after use.</p>
     *
     * @param chunk      the chunk to scan
     * @param poiStorage the POI storage for the owning world
     * @param portalType the registry entry for nether portal POIs
     * @return the number of portal blocks found
     */
    @Unique
    private int chunkis$addPortalPois(
            final WorldChunk chunk, final PointOfInterestStorage poiStorage,
            final RegistryEntry<PointOfInterestType> portalType) {
        chunkis$portalBlockCount = 0;
        chunk.forEachBlockMatchingPredicate(
                NETHER_PORTAL_BLOCK_PREDICATE, (pos, state) -> {
                    chunkis$portalBlockCount++;
                    poiStorage.add(pos, portalType);
                }
        );
        final int count = chunkis$portalBlockCount;
        chunkis$portalBlockCount = 0;
        return count;
    }

    /**
     * Retrieves a mutation delta for the given chunk, ensuring the base state
     * is captured before any modifications occur.
     *
     * @param chunk The WorldChunk instance to capture and track.
     * @return A {@link ChunkDelta} containing the block states and NBT data
     * representing the changes from the base state.
     */
    @Unique
    private ChunkDelta<BlockState, NbtCompound> chunkis$getMutationDeltaWithBaseCapture(final WorldChunk chunk) {
        final ChunkDelta<BlockState, NbtCompound> delta = chunkis$getBlockDelta();
        chunkis$captureBaseChunkBeforeMutation(chunk, delta);

        return delta;
    }

    /**
     * Returns this chunk's typed Chunkis block delta.
     *
     * <p>The cast is safe because {@code CommonChunkMixin} applies
     * {@link ChunkisDeltaDuck} to the base chunk class and this mixin only targets
     * {@link WorldChunk}.</p>
     *
     * @return the typed block/NBT chunk delta
     */
    @Unique
    @SuppressWarnings("unchecked")
    private ChunkDelta<BlockState, NbtCompound> chunkis$getBlockDelta() {
        return (ChunkDelta<BlockState, NbtCompound>) ((ChunkisDeltaDuck) this).chunkis$getDelta();
    }

    /**
     * Casts this mixin instance to its target {@link WorldChunk} using the standard
     * Mixin self-cast pattern.
     *
     * @return this object as a {@link WorldChunk}
     */
    @Unique
    private WorldChunk chunkis$self() {
        return (WorldChunk) (Object) this;
    }


}