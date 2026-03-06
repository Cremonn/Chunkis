package io.liparakis.chunkis.util;

import io.liparakis.chunkis.codec.DefaultBlockMapper;
import io.liparakis.chunkis.codec.DefaultBlockStatePacker;
import io.liparakis.chunkis.codec.interfaces.BlockMapper;
import io.liparakis.chunkis.codec.interfaces.BlockStatePacker;
import io.liparakis.chunkis.model.BlockStateRegistry;
import io.liparakis.chunkis.storage.GlobalIdsPersistence;
import io.liparakis.chunkis.storage.RegionChunkStorage;
import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe helper for managing {@link RegionChunkStorage} instances per
 * world dimension.
 *
 * <p>
 * Storage instances are created lazily and cached by {@link RegistryKey}.
 * Concurrent access is handled by a combination of
 * {@link ConcurrentHashMap#compute} (for atomic create-or-replace on the
 * storage map) and a per-wrapper {@link ReadWriteLock} (for safe close while
 * reads are in flight).
 *
 * <p>
 * <b>Lifecycle:</b> Call {@link #getStorage(ServerWorld)} to obtain a storage
 * instance. Call {@link #closeStorage(ServerWorld)} when a world unloads to
 * release resources and prevent memory leaks.
 *
 * <p>
 * <b>Thread safety:</b> All public methods are thread-safe.
 *
 * @author Liparakis
 * @version 1.2
 */
public final class FabricRegionChunkStorageHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricRegionChunkStorageHelper.class);
    private static final String DIMENSIONS_DIR = "dimensions";
    private static final String CHUNKIS_DIR    = "chunkis";
    private static final String REGIONS_DIR    = "regions";
    private static final String MAPPING_FILE   = "global_ids.json";
    private static final String OVERWORLD_ID   = "overworld";
    private static final BlockRegistryAdapter<Block> REGISTRY_ADAPTER = new FabricBlockRegistryAdapter();
    private static final BlockStateAdapter<Block, BlockState, Property<?>> STATE_ADAPTER = new FabricBlockStateAdapter();
    private static final NbtAdapter<NbtCompound> NBT_ADAPTER = new FabricNbtAdapter();
    private static final BlockState DEFAULT_BLOCK_STATE = Blocks.AIR.getDefaultState();

    /**
     * Active storage wrappers keyed by dimension registry key.
     * {@link ConcurrentHashMap#compute} is used for atomic create-or-replace,
     * eliminating the need for an outer lock on the map itself.
     */
    private static final ConcurrentHashMap<RegistryKey<World>, StorageWrapper> storageMap = new ConcurrentHashMap<>();

    /**
     * Resolved storage directory paths, cached to avoid repeated filesystem
     * traversal and string concatenation on the hot path.
     */
    private static final ConcurrentHashMap<RegistryKey<World>, Path> pathCache = new ConcurrentHashMap<>();

    /**
     * Shared global registry instance, assigned IDs across all dimensions.
     * Lazily initialized on first use via double-checked locking. DCL is
     * required here (rather than the holder idiom) because initialization
     * depends on the runtime server save path.
     */
    private static volatile BlockStateRegistry<Block> globalRegistry;

    private FabricRegionChunkStorageHelper() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns the {@link RegionChunkStorage} for the given world, creating and
     * caching a new instance if one does not exist or has been closed.
     *
     * <p>
     * Fast path (volatile read on the wrapper's {@code open} flag) is lock-free.
     * The slow path uses {@link ConcurrentHashMap#compute} to atomically create
     * or replace the wrapper, so only one thread ever constructs storage for a
     * given dimension at a time.
     *
     * @param world the server world (must not be null)
     * @return the active {@link RegionChunkStorage} for the world's dimension
     * @throws NullPointerException           if world is null
     * @throws StorageInitializationException if storage creation fails
     */
    public static RegionChunkStorage<Block, BlockState, Property<?>, NbtCompound> getStorage(final ServerWorld world) {
        Objects.requireNonNull(world, "ServerWorld cannot be null");

        final RegistryKey<World> key = world.getRegistryKey();

        // Fast path: wrapper present and open — avoid compute overhead.
        final StorageWrapper existing = storageMap.get(key);
        if (existing != null && existing.isOpen()) {
            return existing.getStorage();
        }

        // Slow path: atomic create-or-replace via compute.
        return storageMap.compute(key, (k, current) -> {
            if (current != null && current.isOpen()) return current;
            closeQuietly(current);
            return openStorageWrapper(world, k);
        }).getStorage();
    }

    /**
     * Closes and removes the storage instance for the given world.
     *
     * <p>
     * Should be called when a world unloads to release file handles and prevent
     * memory leaks. Safe to call multiple times or for worlds without storage.
     *
     * @param world the server world (must not be null)
     * @throws NullPointerException if world is null
     */
    public static void closeStorage(final ServerWorld world) {
        Objects.requireNonNull(world, "ServerWorld cannot be null");

        final RegistryKey<World> key = world.getRegistryKey();
        final StorageWrapper wrapper = storageMap.remove(key);
        pathCache.remove(key);

        if (wrapper == null) return;

        try {
            wrapper.close();
            LOGGER.info("Closed Chunkis storage for dimension: {}", key.getValue());
        } catch (final Exception e) {
            LOGGER.error("Error closing Chunkis storage for dimension: {}", key.getValue(), e);
        }
    }

    /**
     * Creates a new {@link StorageWrapper} for the given world, logging success.
     * Wraps any {@link Exception} in a {@link StorageInitializationException}.
     *
     * @param world the server world
     * @param key   the dimension registry key (for logging)
     * @return a new open {@link StorageWrapper}
     * @throws StorageInitializationException if the underlying storage cannot be created
     */
    private static StorageWrapper openStorageWrapper(
            final ServerWorld world,
            final RegistryKey<World> key) {
        try {
            final RegionChunkStorage<Block, BlockState, Property<?>, NbtCompound> storage = buildStorage(world);
            LOGGER.info("Created Chunkis storage for dimension: {}", key.getValue());
            return new StorageWrapper(storage);
        } catch (final Exception e) {
            LOGGER.error("Failed to create Chunkis storage for dimension: {}", key.getValue(), e);
            throw new StorageInitializationException(
                    "Failed to initialize Chunkis storage for " + key.getValue(), e);
        }
    }

    /**
     * Constructs a fully initialized {@link RegionChunkStorage} for the given world.
     *
     * <p>
     * A new {@link BlockStatePacker} is created per storage instance because it
     * holds dimension-specific state. All adapter singletons are shared.
     *
     * @param world the server world
     * @return a ready-to-use {@link RegionChunkStorage}
     * @throws IOException if directory creation or mapping file initialization fails
     */
    private static RegionChunkStorage<Block, BlockState, Property<?>, NbtCompound> buildStorage(
            final ServerWorld world) throws IOException {

        final Path storageDir = resolveAndCreateStorageDir(world);
        final BlockStatePacker<Block, BlockState> packer = new DefaultBlockStatePacker<>(STATE_ADAPTER);
        final BlockStateRegistry<Block> registry = getOrCreateGlobalRegistry(world);
        final BlockMapper<BlockState> mapping = new DefaultBlockMapper<>(registry, STATE_ADAPTER, packer);

        return new RegionChunkStorage<>(storageDir, mapping, STATE_ADAPTER, NBT_ADAPTER, DEFAULT_BLOCK_STATE);
    }

    /**
     * Returns the shared global {@link BlockStateRegistry}, initializing it on
     * the first call via double-checked locking.
     *
     * <p>
     * The holder idiom cannot be used here because initialization requires the
     * runtime server save path, which is not available at class-load time.
     *
     * @param world the server world, used to resolve the save path on first init
     * @return the global block state registry
     * @throws IOException if the mapping file cannot be read or created
     */
    private static BlockStateRegistry<Block> getOrCreateGlobalRegistry(final ServerWorld world) throws IOException {
        BlockStateRegistry<Block> reg = globalRegistry;
        if (reg != null) return reg;

        synchronized (FabricRegionChunkStorageHelper.class) {
            reg = globalRegistry;
            if (reg == null) {
                final Path mappingFile = Objects.requireNonNull(world.getServer())
                        .getSavePath(WorldSavePath.ROOT)
                        .resolve(CHUNKIS_DIR)
                        .resolve(MAPPING_FILE);
                Files.createDirectories(mappingFile.getParent());
                globalRegistry = reg = GlobalIdsPersistence.loadOrInitRegistry(
                        mappingFile, Registries.BLOCK, REGISTRY_ADAPTER, Blocks.AIR);
            }
        }
        return reg;
    }

    /**
     * Returns the storage directory for the given world, creating it on disk if
     * it does not exist. The resolved path is cached to avoid repeated filesystem
     * operations on subsequent calls.
     *
     * @param world the server world
     * @return the resolved and created storage directory path
     * @throws IOException if directory creation fails
     */
    private static Path resolveAndCreateStorageDir(final ServerWorld world) throws IOException {
        final RegistryKey<World> key = world.getRegistryKey();

        final Path cached = pathCache.get(key);
        if (cached != null) return cached;

        final Path storageDir = computeStorageDirectory(world);
        Files.createDirectories(storageDir);
        pathCache.put(key, storageDir);
        return storageDir;
    }

    /**
     * Computes the expected storage directory path for the given world without
     * touching the filesystem.
     *
     * <p>
     * Overworld resolves to {@code <save>/chunkis/regions}.
     * Other dimensions resolve to
     * {@code <save>/dimensions/<namespace>/<path>/chunkis/regions}.
     *
     * @param world the server world
     * @return the computed (not yet created) directory path
     */
    private static Path computeStorageDirectory(final ServerWorld world) {
        // Resolve the dimension identifier once to avoid repeated getValue() calls.
        final Identifier dimId = world.getRegistryKey().getValue();
        Path baseDir = Objects.requireNonNull(world.getServer()).getSavePath(WorldSavePath.ROOT);

        if (!OVERWORLD_ID.equals(dimId.getPath())) {
            baseDir = baseDir.resolve(DIMENSIONS_DIR).resolve(dimId.getNamespace()).resolve(dimId.getPath());
        }

        return baseDir.resolve(CHUNKIS_DIR).resolve(REGIONS_DIR);
    }

    /**
     * Closes the given wrapper, suppressing any exception. Used during atomic
     * replace in {@link #getStorage} to clean up a stale wrapper without
     * aborting the compute lambda.
     *
     * @param wrapper the wrapper to close, may be null
     */
    private static void closeQuietly(final StorageWrapper wrapper) {
        if (wrapper == null) return;
        try {
            wrapper.close();
        } catch (final Exception e) {
            LOGGER.warn("Error closing stale Chunkis storage wrapper", e);
        }
    }

    /**
     * Wraps a {@link RegionChunkStorage} with lifecycle state tracking.
     *
     * <p>
     * A {@link ReadWriteLock} allows concurrent {@link #getStorage()} reads while
     * serializing against {@link #close()}, preventing use-after-close on the
     * underlying storage.
     *
     * <p>
     * {@link #isOpen()} reads the volatile {@code open} flag without acquiring a
     * lock, providing a fast pre-check before entering the read-locked path.
     */
    private static final class StorageWrapper {

        private final RegionChunkStorage<Block, BlockState, Property<?>, NbtCompound> storage;
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        /**
         * Volatile so that {@link #isOpen()} checks outside the lock see the
         * updated value immediately after {@link #close()} completes.
         */
        private volatile boolean open = true;

        StorageWrapper(final RegionChunkStorage<Block, BlockState, Property<?>, NbtCompound> storage) {
            this.storage = Objects.requireNonNull(storage, "Storage cannot be null");
        }

        /**
         * Returns the underlying storage under a read lock, preventing concurrent
         * access while a {@link #close()} is in progress.
         *
         * @return the active storage instance
         * @throws IllegalStateException if the storage has been closed
         */
        RegionChunkStorage<Block, BlockState, Property<?>, NbtCompound> getStorage() {
            lock.readLock().lock();
            try {
                if (!open) throw new IllegalStateException("Storage has been closed");
                return storage;
            } finally {
                lock.readLock().unlock();
            }
        }

        /**
         * Returns true if the storage is still open.
         * Read is lock-free (volatile) and intended as a fast pre-check only.
         *
         * @return true if open
         */
        boolean isOpen() {
            return open;
        }

        /**
         * Closes the underlying storage under a write lock.
         * Idempotent — subsequent calls after the first are no-ops.
         */
        void close() {
            lock.writeLock().lock();
            try {
                if (!open) return;
                storage.close();
                open = false;
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    /**
     * Thrown when a {@link RegionChunkStorage} instance cannot be created for a
     * dimension. Wraps the underlying cause for full stack trace propagation.
     */
    public static final class StorageInitializationException extends RuntimeException {

        /**
         * @param message a description identifying the dimension that failed
         * @param cause   the underlying exception
         */
        public StorageInitializationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}