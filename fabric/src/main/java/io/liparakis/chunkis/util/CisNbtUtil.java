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

    /**
     * Root key under which all Chunkis data is nested in a chunk's NBT.
     */
    public static final String CHUNKIS_DATA_KEY = "ChunkisData";

    /**
     * Chunk status string indicating no terrain has been generated.
     */
    public static final String STATUS_EMPTY = "minecraft:empty";

    /**
     * NBT key for the Minecraft data version integer.
     */
    public static final String DATA_VERSION_KEY = "DataVersion";

    /**
     * NBT key for the chunk generation status string.
     */
    public static final String STATUS_KEY = "Status";

    /**
     * NBT key for the chunk's X coordinate.
     */
    public static final String X_POS_KEY = "xPos";

    /**
     * NBT key for the chunk's Z coordinate.
     */
    public static final String Z_POS_KEY = "zPos";

    /**
     * Root key for vanilla structure metadata in chunk NBT.
     */
    public static final String STRUCTURES_KEY = "structures";

    /**
     * Nested key for serialized structure starts.
     */
    public static final String STRUCTURE_STARTS_KEY = "starts";

    /**
     * Nested key for serialized structure references.
     */
    public static final String STRUCTURE_REFERENCES_KEY = "References";

    /**
     * Root key used inside chunk metadata payloads for Chunkis-owned metadata.
     */
    public static final String CHUNKIS_METADATA_KEY = "chunkis";

    /**
     * Key storing whether replay-time repopulation should be suppressed for a
     * restored chunk.
     */
    public static final String SUPPRESS_INITIAL_REPOPULATION_KEY = "suppress_initial_repopulation";

    /**
     * NBT key written inside {@value #CHUNKIS_DATA_KEY} to indicate that a
     * {@link ChunkDelta} exists for this chunk in the separate CIS storage.
     */
    public static final String HAS_DELTA_KEY = "HasDelta";

    /**
     * NBT key for the entity type registry ID, required by Minecraft's entity deserializer.
     */
    private static final String ENTITY_ID_KEY = "id";

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

        if (delta == null || delta.isEmpty()) {
            return;
        }

        final NbtCompound chunkisData = new NbtCompound();
        chunkisData.putBoolean(HAS_DELTA_KEY, true);
        root.put(CHUNKIS_DATA_KEY, chunkisData);
    }

    /**
     * Copies persisted chunk metadata into the synthetic chunk NBT used for load.
     *
     * <p>
     * At present this metadata is limited to the vanilla {@code structures}
     * compound so structure starts and references survive Chunkis' regenerate-on-load
     * workflow.
     */
    public static void putChunkMetadata(
            final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta) {

        if (delta == null) {
            return;
        }

        final NbtCompound structures = extractPersistedStructureMetadata(delta.getChunkMetadata());
        if (structures != null && !structures.isEmpty()) {
            root.put(STRUCTURES_KEY, structures);
        }
    }

    /**
     * Extracts the vanilla {@code structures} compound from a serialized chunk NBT
     * if it contains any starts or references worth preserving.
     */
    public static NbtCompound extractStructureData(final NbtCompound root) {
        if (root == null || !root.contains(STRUCTURES_KEY)) {
            return null;
        }

        final NbtCompound structures = root.getCompound(STRUCTURES_KEY);
        return hasStructureData(structures) ? structures.copy() : null;
    }

    /**
     * Creates the persisted chunk metadata payload stored in CIS chunk metadata.
     *
     * <p>The payload is an envelope so Chunkis can store both vanilla structure
     * bookkeeping and Chunkis-owned replay suppression flags without changing the
     * surrounding CIS binary format.</p>
     *
     * @param structureData serialized vanilla structure metadata, may be null
     * @param suppressInitialRepopulation whether restored loads should suppress
     *                                    replayed repopulation work
     * @return envelope compound suitable for {@link ChunkDelta#setChunkMetadata}
     */
    public static NbtCompound createChunkMetadata(
            final NbtCompound structureData,
            final boolean suppressInitialRepopulation) {

        final NbtCompound metadata = new NbtCompound();

        if (structureData != null && !structureData.isEmpty()) {
            metadata.put(STRUCTURES_KEY, structureData.copy());
        }

        final NbtCompound chunkisMetadata = new NbtCompound();
        chunkisMetadata.putBoolean(SUPPRESS_INITIAL_REPOPULATION_KEY, suppressInitialRepopulation);
        metadata.put(CHUNKIS_METADATA_KEY, chunkisMetadata);
        return metadata;
    }

    /**
     * Returns the structure metadata persisted inside the chunk metadata payload.
     *
     * <p>Supports both the new envelope format and the legacy v9 payload that
     * stored the raw vanilla {@code structures} compound directly.</p>
     *
     * @param chunkMetadata metadata payload stored in the delta
     * @return copied structure metadata, or null if none exists
     */
    public static NbtCompound extractPersistedStructureMetadata(final NbtCompound chunkMetadata) {
        if (chunkMetadata == null || chunkMetadata.isEmpty()) {
            return null;
        }

        if (chunkMetadata.contains(STRUCTURES_KEY)) {
            final NbtCompound structures = chunkMetadata.getCompound(STRUCTURES_KEY);
            return hasStructureData(structures) ? structures.copy() : null;
        }

        return hasStructureData(chunkMetadata) ? chunkMetadata.copy() : null;
    }

    /**
     * Returns whether restored loads for the given delta should suppress one-time
     * vanilla repopulation work.
     *
     * <p>Legacy chunks that have a Chunkis delta marker but no explicit persisted
     * flag are treated as suppression-enabled by default so old worlds gain the
     * protection immediately.</p>
     *
     * @param root synthetic chunk NBT passed into chunk deserialization
     * @param delta loaded delta for the chunk, may be null
     * @return {@code true} if replay-time repopulation should be suppressed
     */
    public static boolean shouldSuppressInitialRepopulation(
            final NbtCompound root,
            final ChunkDelta<?, ?> delta) {

        if (delta == null || delta.isEmpty()) {
            return false;
        }

        final Object rawMetadata = delta.getChunkMetadata();
        final Boolean explicit = rawMetadata instanceof NbtCompound metadata
                ? readSuppressInitialRepopulationFlag(metadata)
                : null;
        if (explicit != null) {
            return explicit;
        }

        return hasChunkisDeltaMarker(root);
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
     * Returns the explicit persisted suppression flag from the chunk metadata
     * payload, or {@code null} when the payload is legacy and has no explicit flag.
     *
     * @param chunkMetadata payload stored in the delta
     * @return explicit flag value, or null when absent
     */
    private static Boolean readSuppressInitialRepopulationFlag(final NbtCompound chunkMetadata) {
        if (chunkMetadata == null || chunkMetadata.isEmpty()) {
            return null;
        }

        if (!chunkMetadata.contains(CHUNKIS_METADATA_KEY)) {
            return null;
        }

        final NbtCompound chunkisMetadata = chunkMetadata.getCompound(CHUNKIS_METADATA_KEY);
        if (!chunkisMetadata.contains(SUPPRESS_INITIAL_REPOPULATION_KEY)) {
            return null;
        }

        return chunkisMetadata.getBoolean(SUPPRESS_INITIAL_REPOPULATION_KEY);
    }

    /**
     * Returns true if the synthetic root NBT includes the Chunkis delta presence
     * marker.
     *
     * @param root synthetic chunk NBT
     * @return true if the chunk is known to have a persisted Chunkis delta
     */
    private static boolean hasChunkisDeltaMarker(final NbtCompound root) {
        if (root == null || !root.contains(CHUNKIS_DATA_KEY)) {
            return false;
        }

        return root.getCompound(CHUNKIS_DATA_KEY).getBoolean(HAS_DELTA_KEY);
    }

    /**
     * Returns true when the structures compound contains at least one structure
     * start or reference.
     */
    private static boolean hasStructureData(final NbtCompound structures) {
        if (structures == null || structures.isEmpty()) {
            return false;
        }

        return !structures.getCompound(STRUCTURE_STARTS_KEY).isEmpty()
                || !structures.getCompound(STRUCTURE_REFERENCES_KEY).isEmpty();
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
