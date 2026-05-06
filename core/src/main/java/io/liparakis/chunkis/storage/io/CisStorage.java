package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.codec.CisDecoder;
import io.liparakis.chunkis.storage.codec.CisEncoder;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.storage.model.CisConstants;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Region-based storage system for Chunkis chunk deltas.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>32x32 chunk region files</li>
 *   <li>bounded LRU cache of open region files</li>
 *   <li>thread-local encoder, decoder, and compression state</li>
 *   <li>automatic region lookup by chunk position</li>
 * </ul>
 *
 * <p>The storage format writes compressed CIS-encoded deltas into region files.
 * Empty deltas are treated as deletions and clear the corresponding chunk entry
 * from its region.</p>
 *
 * <p><b>Threading:</b> region cache mutation is protected by a lock. Compression
 * and codec objects are stored per thread to avoid allocator churn during
 * concurrent save/load calls.</p>
 *
 * @param <B> block type
 * @param <S> block state type
 * @param <P> property type
 * @param <N> NBT type
 *
 * @author Liparakis
 * @version 1.1
 */
public final class CisStorage<B, S, P, N> {

    /**
     * Bit shift used to convert chunk coordinates into 32x32 region coordinates.
     */
    private static final int REGION_SHIFT = 5;

    /**
     * Root directory where {@code .cis} region files are stored.
     */
    private final Path storageDir;

    /**
     * Global block/state mapping used by this storage instance.
     *
     * <p>The encoder may add entries to this mapping during save, so it is flushed
     * after encoding and before the region payload is written.</p>
     */
    private final CisMapping<B, S, P> mapping;

    /**
     * LRU cache of open region files.
     *
     * <p>Fastutil's linked map does not update recency through plain
     * {@code get}. Use {@code getAndMoveToFirst} and {@code putAndMoveToFirst}
     * for real LRU behavior.</p>
     */
    private final Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile> regionCache;

    /**
     * Protects region cache lookup, insertion, and eviction.
     *
     * <p>Cache hits mutate access order, so the hit path must take the write lock
     * when using real LRU semantics.</p>
     */
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

    /**
     * Per-thread compression state.
     *
     * <p>Compression buffers can be reused safely per thread without synchronizing
     * each save/load operation.</p>
     */
    private final ThreadLocal<CompressionContext> compressionContext =
            ThreadLocal.withInitial(CompressionContext::new);

    /**
     * Per-thread CIS encoder to avoid allocator churn on saves.
     */
    private final ThreadLocal<CisEncoder<S, N>> encoder;

    /**
     * Per-thread CIS decoder to avoid allocator churn on loads.
     */
    private final ThreadLocal<CisDecoder<S, N>> decoder;

    /**
     * Creates a new CIS storage instance.
     *
     * @param storageDir   root storage directory
     * @param mapping      global block/state mapping
     * @param stateAdapter block state adapter
     * @param nbtAdapter   NBT adapter
     * @param airState     canonical air state
     */
    public CisStorage(
            final Path storageDir,
            final CisMapping<B, S, P> mapping,
            final BlockStateAdapter<B, S, P> stateAdapter,
            final NbtAdapter<N> nbtAdapter,
            final S airState
    ) {
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir");
        this.mapping = Objects.requireNonNull(mapping, "mapping");

        final BlockStateAdapter<B, S, P> safeStateAdapter =
                Objects.requireNonNull(stateAdapter, "stateAdapter");
        final NbtAdapter<N> safeNbtAdapter =
                Objects.requireNonNull(nbtAdapter, "nbtAdapter");
        final S safeAirState =
                Objects.requireNonNull(airState, "airState");

        this.regionCache =
                new Object2ObjectLinkedOpenHashMap<>(CisConstants.MAX_CACHED_REGIONS);

        this.encoder = ThreadLocal.withInitial(
                () -> new CisEncoder<>(this.mapping, safeStateAdapter, safeNbtAdapter, safeAirState)
        );

        this.decoder = ThreadLocal.withInitial(
                () -> new CisDecoder<>(this.mapping, safeStateAdapter, safeNbtAdapter, safeAirState)
        );
    }

    /**
     * Saves a chunk delta to CIS storage.
     *
     * <p>If the delta is empty, the chunk entry is cleared from its region file
     * instead. This matters when all edits were reverted and old persisted data
     * must be removed.</p>
     *
     * @param pos   chunk position
     * @param delta chunk delta to save
     * @return {@code true} if the save or clear operation succeeded
     */
    public boolean save(final CisChunkPos pos, final ChunkDelta<S, N> delta) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(delta, "delta");

        try {
            final PreparedSave preparedSave = prepareSave(pos, delta);

            if (!writePrepared(pos, preparedSave)) {
                return false;
            }

            delta.setSourceVersion(CisConstants.VERSION);
            delta.markSaved();
            return true;
        } catch (final IOException e) {
            Chunkis.LOGGER.error("Chunkis: Failed to save CIS chunk {}", pos, e);
            return false;
        }
    }

    /**
     * Encodes an immutable save payload for later compression and region write.
     *
     * <p>This lets a caller move the heavier compression and file I/O work off
     * the server thread while still performing mapping-sensitive CIS encoding at
     * payload build time.</p>
     *
     * @param pos   chunk position
     * @param delta chunk delta to encode
     * @return prepared save payload
     * @throws IOException if encoding or mapping flush fails
     */
    public PreparedSave prepareSave(final CisChunkPos pos, final ChunkDelta<S, N> delta) throws IOException {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(delta, "delta");

        if (delta.isEmpty()) {
            return PreparedSave.clear();
        }

        final byte[] rawData = encoder.get().encode(delta);
        mapping.flush();
        return PreparedSave.write(rawData);
    }

    /**
     * Compresses and writes a previously prepared payload.
     *
     * @param pos          chunk position
     * @param preparedSave prepared save payload
     * @return {@code true} if the write or clear succeeded
     * @throws IOException if region I/O fails
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean writePrepared(final CisChunkPos pos, final PreparedSave preparedSave) throws IOException {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(preparedSave, "preparedSave");

        if (preparedSave.clearChunk()) {
            return clearChunk(pos);
        }

        final byte[] compressedData =
                compressionContext.get().compress(preparedSave.rawData());

        final RegionFile regionFile = getRegionFile(pos, true);

        if (regionFile == null) {
            return false;
        }

        regionFile.write(pos, compressedData);
        return true;
    }

    /**
     * Loads a chunk delta from CIS storage.
     *
     * <p>Missing regions or missing entries return a fresh empty delta. Corrupted
     * entries are cleared from storage and also return an empty delta.</p>
     *
     * @param pos chunk position
     * @return loaded chunk delta, or an empty delta if missing/corrupt
     */
    public ChunkDelta<S, N> load(final CisChunkPos pos) {
        Objects.requireNonNull(pos, "pos");

        try {
            return loadUnchecked(pos);
        } catch (final IOException e) {
            Chunkis.LOGGER.error(
                    "Chunkis: Failed to decode CIS chunk at {}. Clearing corrupted data. Error: {}",
                    pos,
                    e.getMessage()
            );

            clearChunk(pos);
            return newEmptyDelta();
        } catch (final Exception e) {
            Chunkis.LOGGER.error(
                    "Chunkis: Unexpected error loading CIS chunk at {}. Clearing data.",
                    pos,
                    e
            );

            clearChunk(pos);
            return newEmptyDelta();
        }
    }

    /**
     * Loads a chunk delta without deleting the underlying storage entry when
     * decoding fails.
     *
     * <p>This is intended for recovery-oriented workflows such as migration,
     * where preserving the original bytes matters more than aggressive
     * self-healing. Missing regions or chunk slots still return a fresh empty
     * delta.</p>
     *
     * @param pos chunk position
     * @return loaded chunk delta, or an empty delta if the entry is absent
     * @throws IOException if region I/O or decode fails
     */
    public ChunkDelta<S, N> loadWithoutClearing(final CisChunkPos pos) throws IOException {
        Objects.requireNonNull(pos, "pos");
        return loadUnchecked(pos);
    }

    /**
     * Closes all cached region files and releases current-thread codec state.
     *
     * <p>The cache is copied and cleared under the write lock, then files are
     * compacted and closed outside the lock. That keeps the lock held only for
     * cache mutation, not for potentially slow file work.</p>
     */
    public void close() {
        final List<RegionFile> filesToClose = drainRegionCache();

        for (final RegionFile regionFile : filesToClose) {
            closeRegionFile(regionFile);
        }

        compressionContext.remove();
        encoder.remove();
        decoder.remove();
    }

    /**
     * Clears a chunk entry from storage.
     *
     * <p>This does not create a missing region just to clear a non-existent chunk.
     * If the owning region file does not exist, the clear operation is considered
     * successful.</p>
     *
     * @param pos chunk position to clear
     * @return {@code true} if the clear operation succeeded
     */
    private boolean clearChunk(final CisChunkPos pos) {
        try {
            final RegionFile regionFile = getRegionFile(pos, false);

            if (regionFile != null) {
                regionFile.write(pos, null);
            }

            return true;
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to clear CIS chunk {}", pos, e);
            return false;
        }
    }

    /**
     * Shared load implementation used by both destructive and non-destructive
     * callers.
     *
     * <p>Absent regions or chunk entries are represented as empty deltas. Decode
     * failures propagate to the caller so it can decide whether the source entry
     * should be cleared or preserved.</p>
     *
     * @param pos chunk position
     * @return decoded chunk delta, or an empty delta if absent
     * @throws IOException if region I/O, decompression, or decode fails
     */
    private ChunkDelta<S, N> loadUnchecked(final CisChunkPos pos) throws IOException {
        final RegionFile regionFile = getRegionFile(pos, false);

        if (regionFile == null) {
            return newEmptyDelta();
        }

        final byte[] compressedData = regionFile.read(pos);

        if (compressedData == null) {
            return newEmptyDelta();
        }

        final byte[] decompressed;
        try {
            decompressed = compressionContext.get().decompress(compressedData);
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Failed to decompress CIS chunk " + pos, e);
        }

        if (decompressed.length < 8) {
            throw new IOException(
                    "Decompressed CIS data too small for chunk "
                            + pos
                            + ": "
                            + decompressed.length
                            + " bytes compressed to "
                            + compressedData.length
                            + " bytes");
        }

        return decoder.get().decode(decompressed);
    }

    /**
     * Gets or opens the region file containing the given chunk.
     *
     * <p>Cache access uses true LRU semantics. Hits are moved to the front of the
     * linked map. New files are also inserted at the front. Eviction removes from
     * the back.</p>
     *
     * @param pos    chunk position
     * @param create whether the region should be created when missing
     * @return region file, or {@code null} when {@code create == false} and no file exists
     * @throws IOException if the region file cannot be opened
     */
    private RegionFile getRegionFile(
            final CisChunkPos pos,
            final boolean create
    ) throws IOException {
        final RegionKey key = getRegionKey(pos);

        cacheLock.writeLock().lock();
        try {
            final RegionFile existing = regionCache.getAndMoveToFirst(key);

            if (existing != null) {
                return existing;
            }

            if (!create && !Files.exists(regionPath(key))) {
                return null;
            }

            if (regionCache.size() >= CisConstants.MAX_CACHED_REGIONS) {
                evictLeastRecentlyUsedRegion();
            }

            final RegionFile newFile = new RegionFile(storageDir, key.x(), key.z());
            regionCache.putAndMoveToFirst(key, newFile);

            return newFile;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Converts a chunk position into its owning 32x32 region key.
     *
     * @param pos chunk position
     * @return region key
     */
    private static RegionKey getRegionKey(final CisChunkPos pos) {
        return new RegionKey(
                pos.x() >> REGION_SHIFT,
                pos.z() >> REGION_SHIFT
        );
    }

    /**
     * Builds the expected path for a region file.
     *
     * <p>This avoids {@link String#format(String, Object...)} on the region lookup
     * path, which would allocate varargs and formatting machinery.</p>
     *
     * @param key region key
     * @return path to the region file
     */
    private Path regionPath(final RegionKey key) {
        return storageDir.resolve("r." + key.x() + '.' + key.z() + ".cis");
    }

    /**
     * Evicts the least-recently used region file from the cache.
     *
     * <p>Must be called while holding the cache write lock.</p>
     */
    private void evictLeastRecentlyUsedRegion() {
        final RegionFile regionFile = regionCache.removeLast();

        if (regionFile != null) {
            closeRegionFile(regionFile);
        }
    }

    /**
     * Removes all cached region files and returns them for closing.
     *
     * @return snapshot of cached region files
     */
    private List<RegionFile> drainRegionCache() {
        cacheLock.writeLock().lock();
        try {
            final List<RegionFile> filesToClose =
                    new ArrayList<>(regionCache.values());

            regionCache.clear();

            return filesToClose;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Compacts and closes a region file.
     *
     * <p>Errors during compaction/close are logged but do not stop other cached
     * files from being closed.</p>
     *
     * @param regionFile region file to close
     */
    private static void closeRegionFile(final RegionFile regionFile) {
        try {
            regionFile.compact();
        } catch (final Exception e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to compact CIS region file", e);
        }

        try {
            regionFile.close();
        } catch (final Exception e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to close CIS region file", e);
        }
    }

    /**
     * Creates a fresh empty delta.
     *
     * <p>Kept as a helper so missing/corrupt load exits stay consistent and easy
     * to audit.</p>
     *
     * @return empty chunk delta
     */
    private static <S, N> ChunkDelta<S, N> newEmptyDelta() {
        return new ChunkDelta<>();
    }

    /**
     * Immutable encoded payload for deferred compression and write.
     */
    public static final class PreparedSave {
        private final boolean clearChunk;
        private final byte[] rawData;

        private PreparedSave(final boolean clearChunk, final byte[] rawData) {
            this.clearChunk = clearChunk;
            this.rawData = rawData;
        }

        public static PreparedSave clear() {
            return new PreparedSave(true, null);
        }

        public static PreparedSave write(final byte[] rawData) {
            return new PreparedSave(false, Objects.requireNonNull(rawData, "rawData"));
        }

        public boolean clearChunk() {
            return clearChunk;
        }

        public byte[] rawData() {
            return rawData;
        }
    }
}
