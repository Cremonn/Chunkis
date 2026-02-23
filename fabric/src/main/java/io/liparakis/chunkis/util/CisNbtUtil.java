package io.liparakis.chunkis.util;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.ChunkPos;

import java.util.Objects;

/**
 * Utility class for Chunkis NBT operations and key constants.
 *
 * <p>
 * Provides methods for constructing and annotating chunk NBT data with minimal
 * memory allocation. Methods are not thread-safe; callers are responsible for
 * ensuring that NBT compounds are not accessed concurrently.
 *
 * @author Liparakis
 * @version 1.1
 */
public final class CisNbtUtil {

    // -------------------------------------------------------------------------
    // NBT key constants — public for use by serialization/deserialization code
    // -------------------------------------------------------------------------

    /** Root key under which all Chunkis data is nested in a chunk's NBT. */
    public static final String CHUNKIS_DATA_KEY  = "ChunkisData";

    /** Chunk status string indicating no terrain has been generated. */
    public static final String STATUS_EMPTY      = "minecraft:empty";

    /** NBT key for the Minecraft data version integer. */
    public static final String DATA_VERSION_KEY  = "DataVersion";

    /** NBT key for the chunk generation status string. */
    public static final String STATUS_KEY        = "Status";

    /** NBT key for the chunk's X coordinate. */
    public static final String X_POS_KEY         = "xPos";

    /** NBT key for the chunk's Z coordinate. */
    public static final String Z_POS_KEY         = "zPos";

    /**
     * NBT key written inside {@value #CHUNKIS_DATA_KEY} to indicate that a
     * {@link ChunkDelta} exists for this chunk in the separate CIS storage.
     */
    public static final String HAS_DELTA_KEY     = "HasDelta";

    /** NBT key for the entity type registry ID, required by Minecraft's entity deserializer. */
    private static final String ENTITY_ID_KEY    = "id";

    private CisNbtUtil() {
        throw new AssertionError("Utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Creates a minimal NBT compound for a chunk with the required base fields.
     *
     * <p>
     * Sets {@link #STATUS_KEY} to {@link #STATUS_EMPTY} so that terrain is
     * regenerated on load if no other status is written afterward.
     * Fields are written in logical order: version → status → position.
     *
     * @param pos         the chunk position (must not be null)
     * @param dataVersion the Minecraft data version integer
     * @return a new NBT compound populated with the base chunk fields
     * @throws NullPointerException if pos is null
     */
    public static NbtCompound createBaseNbt(final ChunkPos pos, final int dataVersion) {
        Objects.requireNonNull(pos, "ChunkPos cannot be null");

        final NbtCompound nbt = new NbtCompound();
        nbt.putInt(DATA_VERSION_KEY, dataVersion);
        nbt.putString(STATUS_KEY, STATUS_EMPTY);
        nbt.putInt(X_POS_KEY, pos.x);
        nbt.putInt(Z_POS_KEY, pos.z);
        return nbt;
    }

    /**
     * Writes a {@link ChunkDelta} presence marker into the given root NBT compound.
     *
     * <p>
     * The actual delta data is stored separately by {@code CisStorage}. This method
     * only writes a {@value #HAS_DELTA_KEY} flag under {@value #CHUNKIS_DATA_KEY}
     * so that the chunk deserializer knows to look up the delta on load.
     *
     * <p>
     * No marker is written if the delta is null or empty, avoiding unnecessary
     * NBT compound allocation on the common case of unmodified chunks.
     *
     * @param root  the root NBT compound to annotate (must not be null)
     * @param delta the chunk delta to mark, may be null
     * @throws NullPointerException if root is null
     */
    public static void putDelta(
            final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta) {

        Objects.requireNonNull(root, "Root NBT compound cannot be null");

        if (!hasDelta(delta)) return;

        final NbtCompound chunkisData = new NbtCompound();
        chunkisData.putBoolean(HAS_DELTA_KEY, true);
        root.put(CHUNKIS_DATA_KEY, chunkisData);
    }

    /**
     * Ensures the given entity NBT compound contains the required {@code "id"} field.
     *
     * <p>
     * Minecraft's entity deserializer requires every entity NBT to have an
     * {@code "id"} field containing the registry ID string (e.g., {@code "minecraft:pig"}).
     * Some serialization paths omit this field; this method inserts it from the
     * entity's type registry entry if it is absent.
     *
     * @param nbt    the entity NBT compound to check and potentially update
     * @param entity the entity whose type registry ID should be inserted if missing
     */
    public static void ensureEntityIdPresent(final NbtCompound nbt, final Entity entity) {
        if (!nbt.contains(ENTITY_ID_KEY)) {
            nbt.putString(ENTITY_ID_KEY, resolveEntityId(entity));
        }
    }

    // -------------------------------------------------------------------------
    // Guard predicates and helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given delta is non-null and contains at least one change.
     *
     * @param delta the delta to evaluate, may be null
     * @return true if a marker should be written into the chunk NBT
     */
    private static boolean hasDelta(final ChunkDelta<?, ?> delta) {
        return delta != null && !delta.isEmpty();
    }

    /**
     * Resolves the registry ID string for the given entity's type.
     *
     * @param entity the entity whose type to look up
     * @return the registry ID string (e.g., {@code "minecraft:pig"})
     */
    private static String resolveEntityId(final Entity entity) {
        return Registries.ENTITY_TYPE.getId(entity.getType()).toString();
    }
}