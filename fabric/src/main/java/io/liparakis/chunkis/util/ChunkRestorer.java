package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

/**
 * Utility for restoring chunks from Chunkis delta data.
 *
 * <p>
 * Applies player modifications stored in a {@link ChunkDelta} to freshly
 * generated {@link WorldChunk} instances. Restoration is critical for
 * preserving player-made changes across world reloads and chunk regeneration.
 *
 * <h2>Restoration Process</h2>
 * <ol>
 * <li>Validates each block entry against the vanilla snapshot — entries that
 * match worldgen output are skipped and not copied to the runtime delta
 * (automatic delta cleanup).</li>
 * <li>Applies surviving block state changes directly to chunk sections.</li>
 * <li>Restores block entities from NBT.</li>
 * <li>Spawns entities stored in the delta.</li>
 * <li>Copies all validated changes to the runtime delta for future saves.</li>
 * </ol>
 *
 * <p>
 * <b>Thread safety:</b> All methods must execute on the server thread.
 * Entity spawning and chunk modification are not thread-safe.
 *
 * @author Liparakis
 * @version 2.1
 */
public final class ChunkRestorer {

    private static final Logger LOGGER = Chunkis.LOGGER;

    /** Bitmask to extract the local Y coordinate within a section (0–15). */
    private static final int SECTION_Y_MASK = 15;

    private ChunkRestorer() {
        throw new AssertionError("Utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Restores a chunk from delta data, validating against the vanilla snapshot.
     *
     * <p>
     * Visits every entry in {@code protoDelta} via {@link RestorationVisitor}.
     * Block entries that match the vanilla snapshot are discarded (not written
     * to {@code runtimeDelta}), keeping the delta minimal over time.
     *
     * @param world        the server world context
     * @param chunk        the chunk to restore modifications into
     * @param protoDelta   the source delta containing saved modifications
     * @param runtimeDelta the runtime delta to populate with validated changes
     * @param snapshot     the vanilla worldgen snapshot for redundancy checks; may
     *                     be null
     * @return true if at least one redundant entry was detected and discarded
     */
    public static boolean restore(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final VanillaChunkSnapshot snapshot) {

        final RestorationVisitor visitor = new RestorationVisitor(world, chunk, runtimeDelta, snapshot);
        protoDelta.accept(visitor);
        visitor.finishRestoration();
        return visitor.wasOptimized;
    }

    // -------------------------------------------------------------------------
    // Block application (static, no world retention)
    // -------------------------------------------------------------------------

    /**
     * Applies a single block state change directly to a chunk section's palette
     * storage.
     *
     * <p>
     * Validates section existence before modification. Any exception during
     * section access is caught and logged rather than propagated, so a single
     * bad entry cannot abort restoration of the rest of the chunk.
     *
     * @param chunk         the chunk to modify
     * @param chunkPosition the chunk's position (used only for logging)
     * @param localX        local X coordinate (0–15)
     * @param localY        absolute world Y coordinate
     * @param localZ        local Z coordinate (0–15)
     * @param state         the new block state to write
     * @param worldPosition the absolute position (used only for logging)
     * @return true if the block was applied successfully
     */
    private static boolean applyBlockChange(
            final WorldChunk chunk,
            final ChunkPos chunkPosition,
            final int localX,
            final int localY,
            final int localZ,
            final BlockState state,
            final BlockPos worldPosition) {

        try {
            final int sectionIndex = chunk.getSectionIndex(localY);
            final ChunkSection section = chunk.getSection(sectionIndex);

            if (section == null) {
                LOGGER.warn("Null section at index {} for chunk {}", sectionIndex, chunkPosition);
                return false;
            }

            section.setBlockState(localX, localY & SECTION_Y_MASK, localZ, state);
            chunk.removeBlockEntity(worldPosition);
            return true;

        } catch (final Exception e) {
            LOGGER.error("Failed to restore block at {} in chunk {}", worldPosition, chunkPosition, e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // RestorationVisitor
    // -------------------------------------------------------------------------

    /**
     * Visitor that processes each delta entry during restoration.
     *
     * <p>
     * For each block entry, checks whether it is redundant with the vanilla
     * snapshot. Redundant entries are counted but not forwarded to the runtime
     * delta, effectively pruning the delta of stale data over time.
     *
     * <p>
     * <b>Thread safety:</b> Not thread-safe — instances are single-use and
     * must only be driven from the server thread via {@link #restore}.
     */
    private static final class RestorationVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {

        private final ServerWorld world;
        private final WorldChunk chunk;
        private final ChunkPos chunkPosition;
        private final ChunkDelta<BlockState, NbtCompound> runtimeDelta;
        private final VanillaChunkSnapshot snapshot;

        /**
         * Set to true if at least one block entry was skipped as redundant with
         * vanilla.
         */
        boolean wasOptimized = false;

        RestorationVisitor(
                final ServerWorld world,
                final WorldChunk chunk,
                final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
                final VanillaChunkSnapshot snapshot) {
            this.world = world;
            this.chunk = chunk;
            this.chunkPosition = chunk.getPos();
            this.runtimeDelta = runtimeDelta;
            this.snapshot = snapshot;
        }

        // -------------------------------------------------------------------------
        // DeltaVisitor implementation
        // -------------------------------------------------------------------------

        @Override
        public void visitBlock(final int localX, final int localY, final int localZ, final BlockState state) {
            if (isRedundantWithVanilla(localX, localY, localZ, state)) {
                wasOptimized = true;
                return;
            }

            final BlockPos worldPos = chunkPosition.getBlockPos(localX, localY, localZ);
            if (!applyBlockChange(chunk, chunkPosition, localX, localY, localZ, state, worldPos))
                return;

            copyBlockToRuntimeDelta(localX, localY, localZ, state);
        }

        @Override
        public void visitBlockEntity(final int localX, final int localY, final int localZ, final NbtCompound nbt) {
            restoreBlockEntity(localX, localY, localZ, nbt);
        }

        @Override
        public void visitEntity(final NbtCompound nbt) {
            restoreEntity(nbt);
        }

        // -------------------------------------------------------------------------
        // Block helpers
        // -------------------------------------------------------------------------

        /**
         * Returns true if the given state exactly matches the vanilla worldgen state
         * at the same position, indicating it is a redundant delta entry.
         *
         * <p>
         * Returns false when no snapshot is available, treating all entries as
         * non-redundant (conservative — keeps the delta intact).
         *
         * @param localX local X (0–15)
         * @param localY absolute world Y
         * @param localZ local Z (0–15)
         * @param state  the delta block state to compare
         * @return true if the delta entry matches vanilla and can be discarded
         */
        private boolean isRedundantWithVanilla(
                final int localX,
                final int localY,
                final int localZ,
                final BlockState state) {

            if (snapshot == null)
                return false;

            final BlockState vanillaState = snapshot.getVanillaState(localX, localY, localZ);
            return vanillaState != null && vanillaState.equals(state);
        }

        /**
         * Forwards a validated block change to the runtime delta in silent (no-dirty)
         * mode.
         * No-ops if the runtime delta is null.
         *
         * @param localX local X (0–15)
         * @param localY absolute world Y
         * @param localZ local Z (0–15)
         * @param state  the applied block state
         */
        private void copyBlockToRuntimeDelta(
                final int localX,
                final int localY,
                final int localZ,
                final BlockState state) {

            if (runtimeDelta != null) {
                runtimeDelta.addBlockChange(localX, localY, localZ, state, false);
            }
        }

        // -------------------------------------------------------------------------
        // Block entity restoration
        // -------------------------------------------------------------------------

        /**
         * Restores a block entity from NBT into the chunk at the given local
         * coordinates.
         *
         * <p>
         * Skips the position if the current block state does not support a block
         * entity. Skips and logs a warning if NBT deserialization returns null.
         *
         * @param localX local X (0–15)
         * @param localY absolute world Y
         * @param localZ local Z (0–15)
         * @param nbt    the serialized block entity data
         */
        private void restoreBlockEntity(
                final int localX,
                final int localY,
                final int localZ,
                final NbtCompound nbt) {

            final BlockPos worldPos = chunkPosition.getBlockPos(localX, localY, localZ);
            final BlockState currentState = chunk.getBlockState(worldPos);

            if (!currentState.hasBlockEntity()) {
                LOGGER.debug("Skipping block entity at {} — state {} does not support block entities",
                        worldPos, currentState);
                return;
            }

            // Guard: verify the NBT id matches the block entity type the current
            // block actually supports. A mismatch means stale or migrated delta
            // data — skip silently rather than letting createFromNbt throw
            // internally and return null.
            if (!isNbtIdCompatibleWithState(nbt, currentState, worldPos)) {
                return;
            }

            final BlockEntity be = BlockEntity.createFromNbt(
                    worldPos, currentState, nbt, world.getRegistryManager());

            if (be == null) {
                LOGGER.warn("Failed to create block entity from NBT at {}", worldPos);
                return;
            }

            chunk.removeBlockEntity(worldPos);
            chunk.addBlockEntity(be);

            if (runtimeDelta != null) {
                runtimeDelta.addBlockEntityData(localX, localY, localZ, nbt, false);
            }
        }

        /**
         * Returns {@code true} if the {@code id} field in {@code nbt} names a
         * {@link BlockEntityType} that supports {@code state}'s block.
         *
         * <p>
         * This prevents a mismatch between stale CIS delta data (e.g. a
         * {@code sculk_sensor} entry at a position that is now a
         * {@code sculk_catalyst}) from reaching
         * {@link BlockEntity#createFromNbt}, which would throw an
         * {@link IllegalStateException} internally and silently return
         * {@code null}.
         *
         * @param nbt          the block entity NBT; must contain an {@code id} tag
         * @param currentState the block state currently at the target position
         * @param worldPos     position used only for logging on mismatch
         * @return {@code true} if the NBT id is compatible with the block state
         */
        private boolean isNbtIdCompatibleWithState(
                final NbtCompound nbt,
                final BlockState currentState,
                final BlockPos worldPos) {

            final String idStr = nbt.getString("id");
            if (idStr == null || idStr.isEmpty()) {
                LOGGER.debug("Block entity NBT at {} has no id tag — skipping", worldPos);
                return false;
            }

            final Identifier id = Identifier.tryParse(idStr);
            if (id == null) {
                LOGGER.debug("Block entity NBT at {} has unparseable id '{}' — skipping", worldPos, idStr);
                return false;
            }

            final BlockEntityType<?> type = Registries.BLOCK_ENTITY_TYPE.get(id);
            if (type == null) {
                LOGGER.debug("Unknown block entity type '{}' at {} — skipping", idStr, worldPos);
                return false;
            }

            if (!type.supports(currentState)) {
                LOGGER.debug(
                        "Skipping stale block entity '{}' at {} — block {} does not support this type",
                        idStr, worldPos, currentState);
                return false;
            }

            return true;
        }

        // -------------------------------------------------------------------------
        // Entity restoration
        // -------------------------------------------------------------------------

        /**
         * Deserializes and spawns an entity from NBT, checking for UUID conflicts
         * to prevent duplicate spawning on re-load.
         *
         * <p>
         * Uses Minecraft's built-in {@link EntityType#loadEntityWithPassengers}
         * to handle all entity types, including vehicles with passengers.
         *
         * <p>
         * <b>Thread safety:</b> Must be called on the server thread.
         *
         * @param nbt the serialized entity data
         */
        private void restoreEntity(final NbtCompound nbt) {
            EntityType.loadEntityWithPassengers(nbt, world, entity -> {
                if (isEntityAlreadySpawned(entity.getUuid()))
                    return entity;
                world.spawnEntity(entity);
                return entity;
            });

            if (runtimeDelta != null) {
                runtimeDelta.addPendingEntity(nbt);
            }
        }

        public void finishRestoration() {
            if (runtimeDelta != null) {
                runtimeDelta.clearPendingEntities();
            }
        }

        /**
         * Returns true if an entity with the given UUID is already present in the
         * world.
         * Used to prevent duplicate spawning when a delta is applied more than once.
         *
         * @param uuid the UUID to check
         * @return true if the entity already exists
         */
        private boolean isEntityAlreadySpawned(final java.util.UUID uuid) {
            return world.getEntity(uuid) != null;
        }
    }
}