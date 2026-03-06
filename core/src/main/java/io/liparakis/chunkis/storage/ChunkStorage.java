package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.model.ChunkDelta;
import io.liparakis.chunkis.model.CisChunkPos;

/**
 * Interface defining chunk storage operations.
 *
 * @param <S> BlockState type
 * @param <N> NBT type
 */
public interface ChunkStorage<S, N> {

    /**
     * Saves a chunk delta to storage.
     * If the delta is empty, the chunk may be cleared instead.
     *
     * @param pos   the chunk position
     * @param delta the chunk delta to save
     */
    void save(CisChunkPos pos, ChunkDelta<S, N> delta);

    /**
     * Loads a chunk delta from storage.
     *
     * @param pos the chunk position
     * @return the loaded chunk delta, or an empty delta if not found or on error
     */
    ChunkDelta<S, N> load(CisChunkPos pos);

    /**
     * Closes the storage mechanism and cleans up resources.
     */
    void close();
}
