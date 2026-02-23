package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisConstants;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
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
 * @version 2.1
 */
public final class ChunkBlockEntityCapture {

    private static final Logger LOGGER = Chunkis.LOGGER;

    /** NBT key required by Minecraft's block entity deserializer. */
    private static final String BLOCK_ENTITY_ID_KEY = "id";

    private ChunkBlockEntityCapture() {
        throw new AssertionError("Utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Captures a single block entity into the provided delta.
     *
     * <p>
     * Skips removed entities, entities that produce null or empty NBT, and
     * entities whose type has no registry entry. Converts the absolute block
     * position to local chunk coordinates before storing.
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

        final NbtCompound nbt = trySerializeBlockEntity(blockEntity, registryManager);
        if (isEmptyNbt(nbt)) return;

        storeInDelta(blockEntity.getPos(), nbt, delta);
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /**
     * Serializes the given block entity to NBT, ensuring the required
     * {@value #BLOCK_ENTITY_ID_KEY} field is present.
     *
     * <p>
     * Returns null and logs a warning if the block entity's type has no registry
     * entry — such entities cannot be deserialized on load and must not be saved.
     *
     * @param blockEntity     the entity to serialize
     * @param registryManager the registry wrapper for serialization
     * @return the populated NBT compound, or null if the type is unregistered
     */
    @Nullable
    private static NbtCompound trySerializeBlockEntity(
            final BlockEntity blockEntity,
            final RegistryWrapper.WrapperLookup registryManager) {

        final NbtCompound nbt = blockEntity.createNbt(registryManager);

        if (!nbt.contains(BLOCK_ENTITY_ID_KEY)) {
            return injectBlockEntityId(blockEntity, nbt);
        }

        return nbt;
    }

    /**
     * Attempts to inject the registry ID string into an NBT compound that is
     * missing the required {@value #BLOCK_ENTITY_ID_KEY} field.
     *
     * <p>
     * Returns null and logs a warning if the block entity's type is not registered,
     * since saving an unidentifiable entity would produce unloadable data.
     *
     * @param blockEntity the entity whose type ID to look up
     * @param nbt         the NBT compound to populate
     * @return the populated NBT compound, or null if the type is unregistered
     */
    @Nullable
    private static NbtCompound injectBlockEntityId(
            final BlockEntity blockEntity,
            final NbtCompound nbt) {

        final var typeId = BlockEntityType.getId(blockEntity.getType());

        if (typeId == null) {
            LOGGER.warn("Block entity at {} has unregistered type: {}",
                    blockEntity.getPos(), blockEntity.getType());
            return null;
        }

        nbt.putString(BLOCK_ENTITY_ID_KEY, typeId.toString());
        return nbt;
    }

    // -------------------------------------------------------------------------
    // Delta storage
    // -------------------------------------------------------------------------

    /**
     * Stores the given NBT into the delta at local chunk coordinates derived
     * from the given absolute world position.
     *
     * <p>
     * Local X and Z are extracted via bitwise AND with {@link CisConstants#COORD_MASK},
     * which is faster than modulo and correct for non-negative chunk coordinates.
     *
     * @param worldPos the absolute block position
     * @param nbt      the serialized block entity NBT
     * @param delta    the delta to store the entry in
     */
    private static void storeInDelta(
            final BlockPos worldPos,
            final NbtCompound nbt,
            final ChunkDelta<?, NbtCompound> delta) {

        delta.addBlockEntityData(
                worldPos.getX() & CisConstants.COORD_MASK,
                worldPos.getY(),
                worldPos.getZ() & CisConstants.COORD_MASK,
                nbt);
    }

    // -------------------------------------------------------------------------
    // Guard predicates
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given NBT compound is null or contains no entries.
     *
     * @param nbt the compound to check, may be null
     * @return true if there is nothing to save
     */
    private static boolean isEmptyNbt(@Nullable final NbtCompound nbt) {
        return nbt == null || nbt.isEmpty();
    }
}