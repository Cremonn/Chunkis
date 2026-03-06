package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.model.ChunkDelta;
import io.liparakis.chunkis.storage.CisConstants;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

/**
 * Captures block entities from a chunk and serializes them into delta storage.
 *
 * <p>
 * Preserves all block entities present in the chunk at save time, including
 * vanilla worldgen entities (spawners, chests in structures) and player-placed
 * ones (chests, furnaces, signs, etc.).
 *
 * <p>
 * Skips block entities that are removed, produce null or empty NBT, or whose
 * type is not registered (unregistered types cannot be deserialized on load).
 *
 * <p>
 * <b>Thread safety:</b> Must be called on the server thread, as it accesses
 * chunk world state and block entity data.
 *
 * @author Liparakis
 * @version 2.2
 */
public final class ChunkBlockEntityCapture {

    private static final Logger LOGGER = Chunkis.LOGGER;
    private static final String BLOCK_ENTITY_ID_KEY = "id";

    private ChunkBlockEntityCapture() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures a single block entity into the provided delta.
     *
     * <p>
     * The registry ID is resolved <em>before</em> serialization so that
     * unregistered types are rejected without paying the cost of
     * {@link BlockEntity#createNbt}. This is the primary performance
     * improvement over the previous two-method split, which resolved
     * the ID a second time inside the fallback path.
     *
     * <p>
     * Skips removed entities, entities with unregistered types, and
     * entities that produce empty NBT after serialization.
     * Converts the absolute block position to local chunk coordinates
     * before storing.
     *
     * @param blockEntity     the entity to capture
     * @param registryManager the registry wrapper used for NBT serialization
     * @param delta           the delta to store the serialized data in
     */
    public static void captureBlockEntity(
            final BlockEntity blockEntity,
            final RegistryWrapper.WrapperLookup registryManager,
            final ChunkDelta<?, NbtCompound> delta) {

        if (blockEntity.isRemoved()) return;

        // Resolve type ID before serialization — avoids wasting createNbt() on unregistered types.
        final Identifier typeId = BlockEntityType.getId(blockEntity.getType());
        if (typeId == null) {
            LOGGER.warn("Block entity at {} has unregistered type: {}, skipping.",
                    blockEntity.getPos(), blockEntity.getType());
            return;
        }

        final NbtCompound nbt = blockEntity.createNbt(registryManager);

        // createNbt() injects the id key for all registered types in modern Fabric/Yarn,
        // but we guard defensively here without an extra allocation: putString is idempotent
        // and cheaper than a redundant registry lookup through a separate method.
        if (!nbt.contains(BLOCK_ENTITY_ID_KEY)) {
            nbt.putString(BLOCK_ENTITY_ID_KEY, typeId.toString());
        }

        if (nbt.isEmpty()) return;

        // Extract position once to avoid repeated getPos() calls.
        // Local X and Z are derived via bitwise AND with COORD_MASK,
        // which is faster than modulo and correct for non-negative chunk coordinates.
        final BlockPos worldPos = blockEntity.getPos();
        delta.addBlockEntityData(
                worldPos.getX() & CisConstants.COORD_MASK,
                worldPos.getY(),
                worldPos.getZ() & CisConstants.COORD_MASK,
                nbt);
    }
}