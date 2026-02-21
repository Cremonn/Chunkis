package io.liparakis.chunkis.util;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Global registry for tracking dirty chunk deltas that require persistence.
 *
 * <p>
 * This tracker prevents data loss during rapid disconnect/reconnect cycles
 * by keeping dirty deltas in memory even after chunks are unloaded.
 *
 * <p>
 * Thread-safe for concurrent access.
 */
public class GlobalChunkTracker {
    /**
     * Maximum number of deltas to keep in memory for unloaded chunks
     * before they are allowed to be garbage collected.
     */
    private static final int MAX_CACHE_SIZE = 10000;

    /**
     * Map of chunk position keys to deltas.
     * Stores strong references to ensure persistence during unload/load gaps.
     */
    private static final Map<Long, ChunkDelta<?, ?>> dirtyDeltas = new ConcurrentHashMap<>();

    /**
     * Cache for recently unloaded deltas to prevent memory leaks while
     * ensuring they survive the critical re-join window.
     */
    private static final Map<Long, ChunkDelta<?, ?>> unloadCache = new LinkedHashMap<>(
            MAX_CACHE_SIZE, 0.75f,
            true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ChunkDelta<?, ?>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private static final org.slf4j.Logger LOGGER = io.liparakis.chunkis.Chunkis.LOGGER;

    /**
     * Marks a chunk's delta as dirty and tracks it for persistence.
     */
    public static void markDirty(WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck))
            return;

        ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        if (delta == null)
            return;

        long chunkKey = chunk.getPos().toLong();
        dirtyDeltas.put(chunkKey, delta);

        synchronized (unloadCache) {
            unloadCache.put(chunkKey, delta);
        }
    }

    /**
     * Explicitly adds a delta to the tracker and marks it as dirty for persistence.
     */
    public static void addDelta(ChunkPos pos, ChunkDelta<?, ?> delta) {
        long chunkKey = pos.toLong();
        delta.markDirty();
        dirtyDeltas.put(chunkKey, delta);

        synchronized (unloadCache) {
            unloadCache.put(chunkKey, delta);
        }
    }

    /**
     * Retrieves a tracked delta by position.
     */
    public static ChunkDelta<?, ?> getDelta(ChunkPos position) {
        long chunkKey = position.toLong();

        ChunkDelta<?, ?> delta = dirtyDeltas.get(chunkKey);

        if (delta == null) {
            synchronized (unloadCache) {
                delta = unloadCache.get(chunkKey);
            }
        }

        if (LOGGER.isDebugEnabled() && delta != null) {
            LOGGER.debug("Chunkis: Retrieved delta from tracker for {} (Size: {} blocks)",
                    position, delta.getBlockInstructions().size());
        }

        return delta;
    }

    /**
     * Removes a delta from the tracker once it has been successfully persisted to
     * disk.
     */
    public static void markSaved(ChunkPos position) {
        long chunkKey = position.toLong();
        dirtyDeltas.remove(chunkKey);
        synchronized (unloadCache) {
            unloadCache.remove(chunkKey);
        }
    }
}