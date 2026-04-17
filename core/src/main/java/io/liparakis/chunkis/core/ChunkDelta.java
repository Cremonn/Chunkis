package io.liparakis.chunkis.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import io.liparakis.chunkis.storage.CisConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * High-performance storage for modified block data within a chunk.
 * <p>
 * This class maintains a compact representation of block changes, block
 * entities,
 * and entities within a Minecraft chunk. It uses primitive collections from
 * fastutil
 * to minimize garbage collection pressure and avoid boxing/unboxing overhead.
 * </p>
 * <p>
 * The storage mechanism uses:
 * <ul>
 * <li>A palette-based system to deduplicate BlockState references</li>
 * <li>Packed long values to store position and palette index efficiently</li>
 * <li>A position map for O(1) lookups and updates</li>
 * <li>Lazy initialization for block entities to save memory when unused</li>
 * </ul>
 * </p>
 *
 * @param <S> The BlockState type
 * @param <N> The NBT (or data tag) type for entities
 * @see BlockInstruction
 * @see Palette
 */
public final class ChunkDelta<S, N> {
    private static final int INITIAL_CAPACITY = 64;

    /**
     * Packed block instructions stored as long values for memory efficiency
     */
    private long[] packedInstructions;

    /**
     * Current number of block instructions
     */
    private int instructionCount;

    /**
     * Palette mapping BlockStates to compact integer IDs
     */
    private final Palette<S> blockPalette;

    /**
     * Map from packed position to instruction array index for O(1) lookups
     */
    private final Long2IntMap positionMap;

    /**
     * Lazily-initialized map of block entity data, keyed by packed position
     */
    private Long2ObjectMap<N> blockEntities;

    /**
     * Active entities keyed by their runtime Entity ID for O(1) updates.
     */
    private final Int2ObjectMap<N> activeEntities;

    /**
     * Entities loaded from disk or network that haven't been spawned yet.
     */
    private List<N> pendingEntities;

    /**
     * Chunk-level metadata needed to rebuild deterministic worldgen state
     * correctly on reload, such as structure starts/references.
     */
    private N chunkMetadata;

    /**
     * Tracks whether this delta has unsaved changes
     */
    private boolean isDirty;

        /**
     * CIS format version this delta was last decoded from.
     */
    private int sourceVersion;

    /**
     * Marks that this chunk has already gone through its first-worldgen pass and
     * should suppress one-time repopulation side effects on restored loads.
     */
    private boolean suppressInitialRepopulation;

    private final Predicate<S> isEmptyState;

    /**
     * Constructs a new empty ChunkDelta with default initial capacity.
     *
     * @param isEmptyState Predicate to check if a state is considered "empty" (e.g.
     *                     air)
     */
    public ChunkDelta(Predicate<S> isEmptyState) {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        this.blockPalette = new Palette<>();
        this.positionMap = new Long2IntOpenHashMap(INITIAL_CAPACITY);
        this.positionMap.defaultReturnValue(-1);
        this.activeEntities = new Int2ObjectOpenHashMap<>();
        this.pendingEntities = new ArrayList<>();
        this.chunkMetadata = null;
        this.isDirty = false;
        this.sourceVersion = CisConstants.VERSION;
        this.suppressInitialRepopulation = false;
        this.isEmptyState = isEmptyState;
    }

    /**
     * Constructs a new empty ChunkDelta with no empty-state check.
     * Used when the predicate is not available (e.g., during decoding).
     */
    public ChunkDelta() {
        this(s -> false); // Default: nothing is considered empty
    }

    // ==================== Block Changes ====================

    /**
     * Adds or updates a block change at the specified position and marks this delta
     * as dirty.
     */
    public void addBlockChange(int x, int y, int z, S newState) {
        addBlockChange(x, y, z, newState, true);
    }

    /**
     * Adds or updates a block change at the specified position.
     */
    public void addBlockChange(int x, int y, int z, S newState, boolean markDirty) {
        if (newState == null) {
            return;
        }

        final int paletteId = blockPalette.getOrAdd(newState);
        final long posKey = BlockInstruction.packPos(x, y, z);
        final int existingIndex = positionMap.get(posKey);

        if (existingIndex != -1) {
            updateExistingInstruction(x, y, z, paletteId, existingIndex, markDirty);
        } else {
            addNewInstruction(x, y, z, paletteId, posKey, markDirty);
        }

        cleanupBlockEntityIfAir(newState, posKey);
    }

    /**
     * Updates an existing instruction in the packed array.
     *
     * @param x         Local X coordinate.
     * @param y         Local Y coordinate.
     * @param z         Local Z coordinate.
     * @param paletteId The palette index for the block state.
     * @param index     The index in the {@code packedInstructions} array.
     * @param markDirty Whether to mark the delta as dirty.
     */
    private void updateExistingInstruction(int x, int y, int z, int paletteId, int index, boolean markDirty) {
        final long newInstruction = new BlockInstruction((byte) x, y, (byte) z, paletteId).pack();

        if (packedInstructions[index] == newInstruction) {
            return;
        }

        packedInstructions[index] = newInstruction;

        if (markDirty) {
            this.isDirty = true;
        }
    }

    /**
     * Adds a new instruction to the packed array.
     *
     * @param x         Local X coordinate.
     * @param y         Local Y coordinate.
     * @param z         Local Z coordinate.
     * @param paletteId The palette index for the block state.
     * @param posKey    The packed position key.
     * @param markDirty Whether to mark the delta as dirty.
     */
    private void addNewInstruction(int x, int y, int z, int paletteId, long posKey, boolean markDirty) {
        ensureCapacity();
        packedInstructions[instructionCount] = new BlockInstruction((byte) x, y, (byte) z, paletteId).pack();
        positionMap.put(posKey, instructionCount++);

        if (markDirty) {
            this.isDirty = true;
        }
    }

    /**
     * Removes block entity data if the new state is considered "empty" (e.g., air).
     *
     * @param state  The new block state.
     * @param posKey The packed position key.
     */
    private void cleanupBlockEntityIfAir(S state, long posKey) {
        if (isEmptyState.test(state) && blockEntities != null) {
            blockEntities.remove(posKey);
        }
    }

    /**
     * Removes a block change instruction at the specified position.
     */
    public synchronized void removeBlockChange(int x, int y, int z) {
        final long posKey = BlockInstruction.packPos(x, y, z);
        final int index = positionMap.get(posKey);

        if (index == -1) {
            return;
        }

        removeFromPositionMap(posKey);
        removeBlockEntityData(posKey);
        swapWithLastAndShrink(index);

        this.isDirty = true;
    }

    /**
     * Removes the position key from the position map.
     *
     * @param posKey The packed position key.
     */
    private void removeFromPositionMap(long posKey) {
        positionMap.remove(posKey);
    }

    /**
     * Removes associated block entity data for the given position.
     *
     * @param posKey The packed position key.
     */
    private void removeBlockEntityData(long posKey) {
        if (blockEntities != null) {
            blockEntities.remove(posKey);
        }
    }

    /**
     * Removes associated block entity data for the given position and marks delta
     * as dirty.
     */
    public void removeBlockEntityData(int x, int y, int z) {
        final long posKey = BlockInstruction.packPos(x, y, z);
        if (blockEntities != null && blockEntities.containsKey(posKey)) {
            blockEntities.remove(posKey);
            this.isDirty = true;
        }
    }

    /**
     * Removes an instruction by swapping it with the last element and shrinking the
     * array.
     * <p>
     * This avoids costly array copies (O(1) removal).
     *
     * @param index The index of the instruction to remove.
     */
    private void swapWithLastAndShrink(int index) {
        instructionCount--;

        if (index == instructionCount) {
            return;
        }

        final long lastInstruction = packedInstructions[instructionCount];
        packedInstructions[index] = lastInstruction;

        final BlockInstruction lastIns = BlockInstruction.fromPacked(lastInstruction);
        final long lastPosKey = BlockInstruction.packPos(lastIns.x(), lastIns.y(), lastIns.z());
        positionMap.put(lastPosKey, index);
    }

    /**
     * Ensures the instructions array has enough capacity for a new element.
     * Doubles capacity if needed.
     */
    private void ensureCapacity() {
        if (instructionCount < packedInstructions.length) {
            return;
        }

        final long[] newArray = new long[packedInstructions.length << 1];
        System.arraycopy(packedInstructions, 0, newArray, 0, instructionCount);
        packedInstructions = newArray;
    }

    // ==================== Block Entities ====================

    public void addBlockEntityData(int x, int y, int z, N nbt) {
        addBlockEntityData(x, y, z, nbt, true);
    }

    public void addBlockEntityData(int x, int y, int z, N nbt, boolean markDirty) {
        if (nbt == null) {
            return;
        }

        final long key = BlockInstruction.packPos(x, y, z);

        if (blockEntities == null) {
            blockEntities = new Long2ObjectOpenHashMap<>();
        }

        final N existing = blockEntities.get(key);
        if (nbt.equals(existing)) {
            return;
        }

        blockEntities.put(key, nbt);

        if (markDirty) {
            this.isDirty = true;
        }
    }

    public Long2ObjectMap<N> getBlockEntities() {
        return blockEntities == null ? Long2ObjectMaps.emptyMap() : blockEntities;
    }

    // ==================== Entities ====================

    public void putEntity(int entityId, N nbt) {
        if (nbt != null) {
            activeEntities.put(entityId, nbt);
            this.isDirty = true;
        }
    }

    public void removeEntity(int entityId) {
        if (activeEntities.remove(entityId) != null) {
            this.isDirty = true;
        }
    }

    public void clearActiveEntities() {
        if (!activeEntities.isEmpty()) {
            activeEntities.clear();
            this.isDirty = true;
        }
    }

    public void addPendingEntity(N nbt) {
        if (nbt != null) {
            this.pendingEntities.add(nbt);
            this.isDirty = true;
        }
    }

    public void setEntities(List<N> newEntities) {
        setEntities(newEntities, true);
    }

    public void setEntities(List<N> newEntities, boolean markDirty) {
        final List<N> safeEntities = newEntities == null ? Collections.emptyList() : newEntities;

        if (!markDirty) {
            this.pendingEntities = new ArrayList<>(safeEntities);
            return;
        }

        if (this.pendingEntities.equals(safeEntities)) {
            return;
        }

        this.pendingEntities = new ArrayList<>(safeEntities);
        this.isDirty = true;
    }

    public List<N> getEntitiesList() {
        List<N> allEntities = new ArrayList<>(activeEntities.values());
        allEntities.addAll(pendingEntities);
        return allEntities;
    }

    public void clearPendingEntities() {
        if (!this.pendingEntities.isEmpty()) {
            this.pendingEntities.clear();
            this.isDirty = true;
        }
    }

    // ==================== Chunk Metadata ====================

    /**
     * Retrieves the chunk-level metadata tag.
     *
     * @return The metadata as an NBT-like object, or {@code null} if not set.
     */
    public N getChunkMetadata() {
        return chunkMetadata;
    }

    /**
     * Sets the chunk-level metadata and marks this delta as dirty.
     *
     * @param metadata The new metadata to store.
     */
    public void setChunkMetadata(N metadata) {
        setChunkMetadata(metadata, true);
    }

    /**
     * Updates the chunk-level metadata, optionally marking the delta as dirty.
     *
     * @param metadata  The new metadata to store.
     * @param markDirty Whether to mark the delta as dirty if the metadata changed.
     */
    public void setChunkMetadata(N metadata, boolean markDirty) {
        if (Objects.equals(this.chunkMetadata, metadata)) {
            return;
        }

        this.chunkMetadata = metadata;

        if (markDirty) {
            this.isDirty = true;
        }
    }

    // ==================== Queries ====================

    public synchronized List<BlockInstruction> getBlockInstructions() {
        final List<BlockInstruction> list = new ArrayList<>(instructionCount);
        for (int i = 0; i < instructionCount; i++) {
            list.add(BlockInstruction.fromPacked(packedInstructions[i]));
        }
        return list;
    }

    public Palette<S> getBlockPalette() {
        return blockPalette;
    }

    public boolean isEmpty() {
        return instructionCount == 0
                && (blockEntities == null || blockEntities.isEmpty())
                && activeEntities.isEmpty()
                && pendingEntities.isEmpty()
                && chunkMetadata == null;
    }

    // ==================== Dirty Flag ====================

    public boolean isDirty() {
        return isDirty;
    }

    public void markDirty() {
        this.isDirty = true;
    }

    public void markSaved() {
        this.isDirty = false;
    }

    /**
     * Returns the CIS format version this delta was decoded from.
     *
     * @return source CIS version associated with the loaded data
     */
    public int getSourceVersion() {
        return sourceVersion;
    }

    /**
     * Records the CIS format version this delta originated from.
     *
     * @param sourceVersion decoded source format version
     */
    public void setSourceVersion(int sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    /**
     * Returns whether restored loads for this chunk should suppress one-time
     * vanilla repopulation work such as initial passive or structure-linked
     * entity deployment.
     *
     * @return {@code true} if restored loads should suppress repopulation
     */
    public boolean shouldSuppressInitialRepopulation() {
        return suppressInitialRepopulation;
    }

    /**
     * Sets whether restored loads for this chunk should suppress one-time
     * vanilla repopulation work.
     *
     * @param suppressInitialRepopulation {@code true} to suppress replayed
     *                                    repopulation side effects
     */
    public void setSuppressInitialRepopulation(final boolean suppressInitialRepopulation) {
        this.suppressInitialRepopulation = suppressInitialRepopulation;
    }

    // ==================== Visitor ====================

    /**
     * Interface for visiting delta contents.
     */
    public interface DeltaVisitor<S, N> {
        void visitBlock(int x, int y, int z, S state);

        void visitBlockEntity(int x, int y, int z, N nbt);

        void visitEntity(N nbt);
    }

    public void accept(DeltaVisitor<S, N> visitor) {
        // 1. Visit block changes
        for (int i = 0; i < instructionCount; i++) {
            long packed = packedInstructions[i];
            int paletteIndex = (int) (packed >> 32);
            S state = blockPalette.get(paletteIndex);

            if (state == null) {
                continue;
            }

            int x = BlockInstruction.unpackX(packed);
            int y = BlockInstruction.unpackY(packed);
            int z = BlockInstruction.unpackZ(packed);

            visitor.visitBlock(x, y, z, state);
        }

        // 2. Visit block entities
        if (blockEntities != null && !blockEntities.isEmpty()) {
            for (Long2ObjectMap.Entry<N> entry : blockEntities.long2ObjectEntrySet()) {
                long pos = entry.getLongKey();
                int x = BlockInstruction.unpackX(pos);
                int y = BlockInstruction.unpackY(pos);
                int z = BlockInstruction.unpackZ(pos);

                visitor.visitBlockEntity(x, y, z, entry.getValue());
            }
        }

        // 3. Visit global entities
        List<N> allEntities = getEntitiesList();
        if (!allEntities.isEmpty()) {
            for (N nbt : allEntities) {
                visitor.visitEntity(nbt);
            }
        }
    }
}
