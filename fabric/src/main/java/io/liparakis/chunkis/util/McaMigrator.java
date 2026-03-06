package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.model.ChunkDelta;
import io.liparakis.chunkis.model.CisChunkPos;
import io.liparakis.chunkis.storage.RegionChunkStorage;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.*;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot migrator that converts vanilla {@code .mca} region files into the
 * Chunkis CIS format for a given server world.
 *
 * <h2>Migration Process</h2>
 * <ol>
 * <li>Scans the world's {@code region/} directory for {@code .mca} files.</li>
 * <li>Submits one migration task per region file to a bounded thread pool,
 * leaving one logical core free for the server thread.</li>
 * <li>For each chunk within a region file, deserializes the vanilla NBT into a
 * {@link ProtoChunk}, extracts block states, block entities, and entities into
 * a {@link ChunkDelta}, then persists it via {@link RegionChunkStorage}.</li>
 * <li>Renames the original {@code .mca} file to {@code .mca.backup} after
 * conversion to prevent re-migration on subsequent server starts.</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * Parallel migration is safe: {@link RegionFile} reads are synchronized
 * internally, the region-file cache is guarded by a {@code ReadWriteLock},
 * {@code CisMapping} uses RW-locking internally, and compression state is
 * {@code ThreadLocal}. The sole external synchronization point is the
 * {@code PointOfInterestStorage} access in {@link #migrateRegionFile} uses a
 * per-call {@code synchronized} block because its internal
 * {@code Long2ObjectOpenHashMap} is not thread-safe.
 *
 * @author Liparakis
 * @version 1.2
 */
public final class McaMigrator {

    private static final Logger LOGGER = Chunkis.LOGGER;

    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int REGION_SIZE = 32;

    /**
     * Number of threads used for parallel region-file migration.
     * One logical core is reserved for the server thread.
     */
    private static final int MIGRATION_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private McaMigrator() {
        throw new AssertionError("Utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Migrates all {@code .mca} region files found in the world's region directory
     * to the Chunkis CIS format.
     *
     * <p>
     * No-ops if the region directory does not exist. Region file paths are
     * collected up-front so the {@link DirectoryStream} is not held open across
     * I/O operations. Each region file is converted on a worker thread; all
     * results are awaited before returning.
     *
     * @param world the server world whose region files should be migrated
     */
    public static void migrateWorld(final ServerWorld world) {
        final Identifier dimId = world.getRegistryKey().getValue();
        final Path regionDir = resolveMcaRegionDir(world);
        LOGGER.info("Checking MCA region directory for world {}: {}", dimId, regionDir);

        if (!Files.exists(regionDir)) {
            LOGGER.info("No MCA region directory found at {}; skipping migration.", regionDir);
            return;
        }

        final List<int[]> regions = collectRegionCoords(regionDir, dimId);
        if (regions.isEmpty()) return;

        @SuppressWarnings({"rawtypes"})
        final RegionChunkStorage storage = FabricRegionChunkStorageHelper.getStorage(world);
        final AtomicInteger totalMigrated = new AtomicInteger();
        final ExecutorService executor = Executors.newFixedThreadPool(MIGRATION_THREADS);

        try {
            final List<Future<?>> futures = new ArrayList<>(regions.size());
            for (final int[] coords : regions) {
                final int rx = coords[0], rz = coords[1];
                final Path mcaPath = regionDir.resolve("r." + rx + "." + rz + ".mca");
                futures.add(executor.submit(() -> {
                    final int count = migrateRegionFile(world, storage, mcaPath, rx, rz);
                    totalMigrated.addAndGet(count);
                }));
            }
            awaitAll(futures);
        } finally {
            executor.shutdown();
        }

        LOGGER.info("Chunkis MCA migration complete for world {}. Converted {} chunks total.",
                dimId, totalMigrated.get());
    }

    // -------------------------------------------------------------------------
    // Region collection
    // -------------------------------------------------------------------------

    /**
     * Scans {@code regionDir} for {@code .mca} files matching
     * {@link #REGION_FILE_PATTERN} and returns a list of {@code [rx, rz]}
     * coordinate pairs.
     *
     * <p>
     * All paths are resolved before any I/O is performed, so the
     * {@link DirectoryStream} is closed as soon as iteration is complete.
     *
     * @param regionDir the directory to scan
     * @param dimId     the dimension identifier, used only for error logging
     * @return a list of {@code [rx, rz]} coordinate pairs; empty if none found
     */
    private static List<int[]> collectRegionCoords(final Path regionDir, final Identifier dimId) {
        final List<int[]> regions = new ArrayList<>();
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (final Path path : stream) {
                final Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    regions.add(new int[]{
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2))
                    });
                }
            }
        } catch (final IOException e) {
            LOGGER.error("Failed to iterate region directory for dimension {}", dimId, e);
        }
        return regions;
    }

    // -------------------------------------------------------------------------
    // Region file migration
    // -------------------------------------------------------------------------

    /**
     * Converts all chunks in a single {@code .mca} region file to the CIS format.
     *
     * <p>
     * Opens the region file read-only, iterates all 32×32 chunk slots, and for
     * each present chunk deserializes the vanilla NBT, builds a
     * {@link ChunkDelta}, and persists it via {@code storage}. The original
     * {@code .mca} file is renamed to {@code .mca.backup} after all chunks have
     * been processed.
     *
     * <p>
     * {@code PointOfInterestStorage} access is synchronized per-call to guard
     * its non-thread-safe internal map.
     *
     * @param world   the server world (used for registry and POI access)
     * @param storage the CIS storage to persist converted deltas into
     * @param mcaPath the path to the {@code .mca} file to convert
     * @param rx      region X coordinate
     * @param rz      region Z coordinate
     * @return the number of chunks successfully converted
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int migrateRegionFile(
            final ServerWorld world,
            final RegionChunkStorage storage,
            final Path mcaPath,
            final int rx,
            final int rz) {

        int migrated = 0;
        final StorageKey storageKey = new StorageKey("chunk", world.getRegistryKey(), "chunk");
        LOGGER.info("Chunkis migrator: converting {} to CIS...", mcaPath.getFileName());

        try (final RegionFile regionFile = new RegionFile(storageKey, mcaPath, mcaPath.getParent(), true)) {
            final BlockPos.Mutable mutablePos = new BlockPos.Mutable();
            final PalettesFactory palettesFactory = PalettesFactory.fromRegistryManager(world.getRegistryManager());

            for (int x = 0; x < REGION_SIZE; x++) {
                for (int z = 0; z < REGION_SIZE; z++) {
                    final ChunkPos globalPos = new ChunkPos((rx << 5) + x, (rz << 5) + z);

                    try (final DataInputStream in = regionFile.getChunkInputStream(globalPos)) {
                        if (in == null) continue;

                        final NbtCompound nbt = NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
                        if (nbt == null) continue;

                        // PointOfInterestStorage is not thread-safe: synchronize
                        // only this call so its internal Long2ObjectOpenHashMap
                        // is never mutated by two threads at once.
                        final ProtoChunk proto;
                        synchronized (world.getPointOfInterestStorage()) {
                            proto = Objects.requireNonNull(
                                            SerializedChunk.fromNbt(world, palettesFactory, nbt))
                                    .convert(world, world.getPointOfInterestStorage(), storageKey, globalPos);
                        }

                        final ChunkDelta<BlockState, NbtCompound> delta = buildChunkDelta(proto, globalPos, mutablePos);
                        if (!delta.isEmpty()) {
                            storage.save(new CisChunkPos(globalPos.x, globalPos.z), delta);
                            migrated++;
                        }
                    } catch (final Exception e) {
                        LOGGER.error("Failed to migrate chunk {} in {}", globalPos, mcaPath.getFileName(), e);
                    }
                }
            }
        } catch (final Exception e) {
            LOGGER.error("Failed to read region file {}", mcaPath.getFileName(), e);
        }

        backupRegionFile(mcaPath);
        LOGGER.info("Finished {}. Converted {} chunks.", mcaPath.getFileName(), migrated);
        return migrated;
    }

    // -------------------------------------------------------------------------
    // Delta construction
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link ChunkDelta} from a deserialized {@link ProtoChunk}.
     *
     * <p>
     * Iterates all block positions within the chunk's vertical range, recording
     * non-air block states. Block entity NBT entries and entity NBT compounds are
     * appended afterward.
     *
     * <p>
     * {@code mutablePos} is reused across all iterations to avoid allocating
     * approximately 98,000 {@link BlockPos} objects per chunk column.
     *
     * @param proto     the deserialized proto chunk
     * @param globalPos the chunk's global position (used to compute world X/Z)
     * @param mutablePos a reusable mutable block position (caller-owned)
     * @return a delta containing all non-air blocks, block entities, and entities
     */
    private static ChunkDelta<BlockState, NbtCompound> buildChunkDelta(
            final ProtoChunk proto,
            final ChunkPos globalPos,
            final BlockPos.Mutable mutablePos) {

        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();
        final int startX = globalPos.getStartX();
        final int startZ = globalPos.getStartZ();
        final int bottomY = proto.getBottomY();
        final int topY = bottomY + proto.getHeight();

        // Block states — reuse mutablePos to minimize allocation pressure.
        for (int by = bottomY; by < topY; by++) {
            for (int bx = 0; bx < 16; bx++) {
                for (int bz = 0; bz < 16; bz++) {
                    mutablePos.set(startX + bx, by, startZ + bz);
                    final BlockState state = proto.getBlockState(mutablePos);
                    if (state != null && !state.isAir()) {
                        delta.addBlockChange(bx, by, bz, state);
                    }
                }
            }
        }

        // Block entities — use entrySet() to avoid a second map lookup per key.
        for (final Map.Entry<BlockPos, NbtCompound> entry : proto.getBlockEntityNbts().entrySet()) {
            final NbtCompound beNbt = entry.getValue();
            if (beNbt != null) {
                final BlockPos bePos = entry.getKey();
                delta.addBlockEntityData(bePos.getX(), bePos.getY(), bePos.getZ(), beNbt);
            }
        }

        // Entities.
        for (final NbtCompound entityNbt : proto.getEntities()) {
            delta.addPendingEntity(entityNbt);
        }

        return delta;
    }

    // -------------------------------------------------------------------------
    // Path resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves the {@code region/} directory for the given world dimension.
     *
     * <p>
     * The Minecraft server already places dimension sub-folders correctly under
     * {@link WorldSavePath#ROOT}; this method only appends {@code region/}.
     * Overworld resolves to {@code <save>/region}; other dimensions resolve to
     * {@code <save>/dimensions/<namespace>/<path>/region}.
     *
     * @param world the server world whose region directory to resolve
     * @return the resolved region directory path (may not exist on disk)
     */
    private static Path resolveMcaRegionDir(final ServerWorld world) {
        final Path root = Objects.requireNonNull(world.getServer()).getSavePath(WorldSavePath.ROOT);
        final Identifier dimId = world.getRegistryKey().getValue();
        final boolean isOverworld = "minecraft".equals(dimId.getNamespace()) && "overworld".equals(dimId.getPath());

        final Path base = isOverworld
                ? root
                : root.resolve("dimensions").resolve(dimId.getNamespace()).resolve(dimId.getPath());

        return base.resolve("region");
    }

    // -------------------------------------------------------------------------
    // Lifecycle helpers
    // -------------------------------------------------------------------------

    /**
     * Waits for all submitted migration futures to complete, logging any
     * task-level failures without aborting remaining tasks.
     *
     * @param futures the futures to await
     */
    private static void awaitAll(final List<Future<?>> futures) {
        for (final Future<?> future : futures) {
            try {
                future.get();
            } catch (final Exception e) {
                LOGGER.error("A region migration task failed", e);
            }
        }
    }

    /**
     * Renames the given {@code .mca} file to {@code .mca.backup}, replacing any
     * existing backup. This prevents the migrator from re-processing the file on
     * subsequent server starts.
     *
     * @param mcaPath the path to the file to back up
     */
    private static void backupRegionFile(final Path mcaPath) {
        final Path backupPath = mcaPath.resolveSibling(mcaPath.getFileName() + ".backup");
        try {
            Files.move(mcaPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Backed up {} → {}", mcaPath.getFileName(), backupPath.getFileName());
        } catch (final IOException e) {
            LOGGER.error("Failed to back up {}", mcaPath.getFileName(), e);
        }
    }
}