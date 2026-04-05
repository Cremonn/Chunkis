package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ChunkRestorer {

    private static final Logger LOGGER = Chunkis.LOGGER;
    private static final int SECTION_Y_MASK = 15;

    private ChunkRestorer() {
        throw new AssertionError("Utility class");
    }

    public static boolean restore(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final VanillaChunkSnapshot snapshot) {

        final RestorationVisitor visitor = new RestorationVisitor(world, chunk, runtimeDelta, snapshot);
        visitor.cleanupReplayedEntities(protoDelta);
        protoDelta.accept(visitor);
        visitor.finishRestoration();
        return visitor.wasOptimized;
    }

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

    private static final class RestorationVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {

        private final ServerWorld world;
        private final WorldChunk chunk;
        private final ChunkPos chunkPosition;
        private final ChunkDelta<BlockState, NbtCompound> runtimeDelta;
        private final VanillaChunkSnapshot snapshot;
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

        void cleanupReplayedEntities(final ChunkDelta<BlockState, NbtCompound> sourceDelta) {
            if (sourceDelta == null || !sourceDelta.shouldSuppressInitialRepopulation()) {
                return;
            }

            final Set<UUID> allowedUuids = collectPersistedEntityUuids(sourceDelta.getEntitiesList());
            final List<Entity> liveEntities = world.getOtherEntities(
                    null,
                    new Box(
                            chunkPosition.getStartX(),
                            world.getBottomY(),
                            chunkPosition.getStartZ(),
                            chunkPosition.getEndX() + 1,
                            world.getTopYInclusive() + 1,
                            chunkPosition.getEndZ() + 1));

            for (final Entity entity : liveEntities) {
                if (entity instanceof PlayerEntity) {
                    continue;
                }
                if (allowedUuids.contains(entity.getUuid())) {
                    continue;
                }
                entity.discard();
            }
        }

        @Override
        public void visitBlock(final int localX, final int localY, final int localZ, final BlockState state) {
            if (isRedundantWithVanilla(localX, localY, localZ, state)) {
                wasOptimized = true;
                return;
            }

            final BlockPos worldPos = chunkPosition.getBlockPos(localX, localY, localZ);
            if (!applyBlockChange(chunk, chunkPosition, localX, localY, localZ, state, worldPos)) {
                return;
            }

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

        private boolean isRedundantWithVanilla(
                final int localX,
                final int localY,
                final int localZ,
                final BlockState state) {

            if (snapshot == null) {
                return false;
            }

            final BlockState vanillaState = snapshot.getVanillaState(localX, localY, localZ);
            return vanillaState != null && vanillaState.equals(state);
        }

        private void copyBlockToRuntimeDelta(
                final int localX,
                final int localY,
                final int localZ,
                final BlockState state) {

            if (runtimeDelta != null) {
                runtimeDelta.addBlockChange(localX, localY, localZ, state, false);
            }
        }

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

        private void restoreEntity(final NbtCompound nbt) {
            try {
                EntityType.loadEntityWithPassengers(nbt, world, SpawnReason.LOAD, entity -> {
                    if (isEntityAlreadySpawned(entity.getUuid())) {
                        return entity;
                    }

                    world.spawnEntity(entity);
                    return entity;
                });

                if (runtimeDelta != null) {
                    runtimeDelta.addPendingEntity(nbt);
                }

            } catch (final Exception e) {
                LOGGER.error("Failed to restore entity in chunk {}", chunkPosition, e);
            }
        }

        public void finishRestoration() {
            if (runtimeDelta != null) {
                runtimeDelta.clearPendingEntities();
            }
        }

        private boolean isEntityAlreadySpawned(final UUID uuid) {
            return world.getEntity(uuid) != null;
        }

        private Set<UUID> collectPersistedEntityUuids(final List<NbtCompound> entities) {
            final Set<UUID> uuids = new HashSet<>();

            for (final NbtCompound nbt : entities) {
                if (nbt != null && nbt.containsUuid("UUID")) {
                    uuids.add(nbt.getUuid("UUID"));
                }
            }

            return uuids;
        }
    }
}
