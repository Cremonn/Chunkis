package io.liparakis.chunkis.util;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.model.ChunkDelta;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Global registry for tracking dirty chunk deltas that require persistence.
 *
 * <p>
 * Maintains two complementary stores:
 * <ul>
 * <li><b>Dirty delta map:</b> Strong references to deltas for all actively
 * dirty chunks.
 * Entries are removed via {@link #markSaved(ChunkPos)} after a successful disk
 * write.</li>
 * <li><b>Unload cache:</b> Size-limited LRU cache ({@value #MAX_CACHE_SIZE}
 * entries) that
 * retains recently unloaded deltas as a safety net for the critical
 * unload-reload gap
 * during player disconnects. LRU eviction prevents unbounded memory
 * growth.</li>
 * </ul>
 *
 * <p>
 * <b>Thread safety:</b> The dirty delta map uses {@link ConcurrentHashMap} for
 * lock-free
 * concurrent access. The unload cache uses a synchronized {@link LinkedHashMap}
 * because
 * {@link LinkedHashMap} is not thread-safe and requires external locking to
 * maintain its
 * access-order invariant correctly.
 *
 * <p>
 * Chunk references passed into this class are never retained — only the
 * {@link ChunkDelta} extracted from the duck interface and the {@code long}
 * chunk key
 * are stored.
 *
 * @author Liparakis
 * @version 1.1
 */
public final class GlobalChunkTracker {

    /**
     * Maximum number of deltas to retain in the unload cache.
     * Oldest-accessed entries are evicted once this limit is exceeded.
     */
    private static final int MAX_CACHE_SIZE = 10_000;

    /**
     * Active dirty deltas awaiting persistence. Uses {@link ConcurrentHashMap}
     * for lock-free concurrent reads and writes without external synchronization.
     */
    private static final Map<Long, ChunkDelta<?, ?>> dirtyDeltas = new ConcurrentHashMap<>();

    /**
     * LRU cache of recently unloaded deltas. Retained as a safety net so that
     * deltas survive the unload-reload gap during disconnect and server shutdown.
     *
     * <p>
     * Must be accessed under {@code synchronized (unloadCache)} because
     * {@link LinkedHashMap} access-order tracking is not thread-safe.
     */
    private static final Map<Long, ChunkDelta<?, ?>> unloadCache = new LinkedHashMap<>(
            MAX_CACHE_SIZE, 0.75f, /* accessOrder= */ true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, ChunkDelta<?, ?>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private GlobalChunkTracker() {
        throw new AssertionError("Utility class");
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Marks a chunk's delta as dirty and caches it for persistence.
     *
     * <p>
     * No-ops if the chunk does not implement {@link ChunkisDeltaDuck}, or if its
     * delta is null or empty. The chunk reference itself is not retained — only
     * its position key and delta are stored.
     *
     * @param chunk the chunk whose delta should be tracked
     */
    public static void markDirty(final WorldChunk chunk) {
        if (!isChunkisDuck(chunk))
            return;

        final ChunkDelta<?, ?> delta = asDuck(chunk).chunkis$getDelta();
        if (isDeltaAbsent(delta))
            return;

        putDelta(chunk.getPos().toLong(), delta);
    }

    /**
     * Registers a delta for the given position and marks it dirty for persistence.
     *
     * <p>
     * Intended for cases where the chunk is not directly available (e.g., during
     * server-side delta construction from storage).
     *
     * @param pos   the chunk position
     * @param delta the delta to register
     */
    public static void addDelta(final ChunkPos pos, final ChunkDelta<?, ?> delta) {
        delta.markDirty();
        putDelta(pos.toLong(), delta);
    }

    /**
     * Removes a delta from active dirty tracking after a successful disk write.
     *
     * <p>
     * The delta is intentionally retained in the unload cache as a safety net
     * for shutdown re-saves. LRU eviction ({@value #MAX_CACHE_SIZE}) prevents
     * unbounded memory growth.
     *
     * @param position the chunk position that was successfully saved
     */
    public static void markSaved(final ChunkPos position) {
        dirtyDeltas.remove(position.toLong());
        // Intentionally NOT removed from unloadCache — safety net for shutdown
        // re-saves.
    }

    /**
     * Clears all tracked deltas from both the active dirty map and the unload
     * cache.
     *
     * <p>
     * Call this when the server stops or when a client disconnects from a
     * singleplayer
     * world to prevent memory leaks, as static collections will otherwise retain
     * up to {@value #MAX_CACHE_SIZE} deltas indefinitely.
     */
    public static void clear() {
        dirtyDeltas.clear();
        synchronized (unloadCache) {
            unloadCache.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Retrieves a tracked delta by chunk position.
     *
     * <p>
     * Checks the dirty delta map first (fast, lock-free). Falls back to the
     * unload cache if not found there.
     *
     * @param position the chunk position to look up
     * @return the tracked delta, or null if not found in either store
     */
    public static ChunkDelta<?, ?> getDelta(final ChunkPos position) {
        final long chunkKey = position.toLong();

        final ChunkDelta<?, ?> active = dirtyDeltas.get(chunkKey);
        if (active != null)
            return active;

        return getFromUnloadCache(chunkKey);
    }

    /**
     * Returns an unmodifiable snapshot of all chunk positions with pending dirty
     * deltas.
     *
     * @return set of chunk positions awaiting persistence
     */
    public static Set<ChunkPos> getPendingPositions() {
        return dirtyDeltas.keySet().stream()
                .map(ChunkPos::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Puts the given delta into both the dirty map and the unload cache.
     * Centralizes the dual-write so neither store is accidentally omitted.
     *
     * @param chunkKey the packed chunk position key
     * @param delta    the delta to store
     */
    private static void putDelta(final long chunkKey, final ChunkDelta<?, ?> delta) {
        dirtyDeltas.put(chunkKey, delta);
        putInUnloadCache(chunkKey, delta);
    }

    /**
     * Inserts the given entry into the unload cache under the cache's own lock.
     *
     * @param chunkKey the packed chunk position key
     * @param delta    the delta to cache
     */
    private static void putInUnloadCache(final long chunkKey, final ChunkDelta<?, ?> delta) {
        synchronized (unloadCache) {
            unloadCache.put(chunkKey, delta);
        }
    }

    /**
     * Retrieves an entry from the unload cache under the cache's own lock.
     *
     * @param chunkKey the packed chunk position key
     * @return the cached delta, or null if not present
     */
    private static ChunkDelta<?, ?> getFromUnloadCache(final long chunkKey) {
        synchronized (unloadCache) {
            return unloadCache.get(chunkKey);
        }
    }

    // -------------------------------------------------------------------------
    // Guard predicates
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given chunk implements {@link ChunkisDeltaDuck}.
     *
     * @param chunk the chunk to test
     * @return true if the chunk can carry a delta
     */
    private static boolean isChunkisDuck(final WorldChunk chunk) {
        return chunk instanceof ChunkisDeltaDuck;
    }

    /**
     * Casts the given chunk to {@link ChunkisDeltaDuck}.
     * Only call after confirming {@link #isChunkisDuck(WorldChunk)} returns true.
     *
     * @param chunk the chunk to cast
     * @return the chunk as a ChunkisDeltaDuck
     */
    private static ChunkisDeltaDuck asDuck(final WorldChunk chunk) {
        return (ChunkisDeltaDuck) chunk;
    }

    /**
     * Returns true if the given delta is null or contains no changes.
     *
     * @param delta the delta to evaluate, may be null
     * @return true if the delta should be skipped
     */
    private static boolean isDeltaAbsent(final ChunkDelta<?, ?> delta) {
        return delta == null || delta.isEmpty();
    }
}