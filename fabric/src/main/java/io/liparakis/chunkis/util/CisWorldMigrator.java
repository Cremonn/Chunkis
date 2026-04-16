package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.migrator.CisMigrationReport;
import io.liparakis.chunkis.migrator.CisStorageMigrator;
import io.liparakis.chunkis.migrator.CisVersionMap;
import io.liparakis.chunkis.storage.CisStorage;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Upgrades existing CIS region files to the latest CIS format version.
 *
 * <p>Invoked during world load so that all CIS storage is current before
 * gameplay touches it. This is intentionally separate from {@link McaMigrator}:
 * MCA import converts vanilla region files into CIS; this class upgrades
 * CIS-to-CIS across format versions.
 *
 * <p><b>Threading:</b> {@link #migrateWorld} is called from the background
 * migration thread spawned by {@code MigrationCommand}. No world state is
 * mutated; only CIS storage files on disk are read and rewritten.
 */
public final class CisWorldMigrator {

    private static final Logger LOGGER = Chunkis.LOGGER;

    /** Relative path appended to a dimension's base directory to reach CIS region files. */
    private static final String CIS_REGION_SUBPATH = "chunkis/regions";

    /** Dimension path value that maps to the overworld, which uses the root save directory. */
    private static final String OVERWORLD_PATH = "overworld";

    /** Root-relative directory that holds non-overworld dimension data. */
    private static final String DIMENSIONS_SUBPATH = "dimensions";

    private CisWorldMigrator() {
        throw new AssertionError("Utility class");
    }

    /**
     * Upgrades the world's existing CIS storage to the latest supported format.
     *
     * <p>Returns immediately with an empty report if the CIS region directory
     * does not exist, avoiding unnecessary I/O.
     *
     * <p><b>Threading:</b> Must only be called from the background migration thread.
     *
     * @param world the dimension whose Chunkis storage should be upgraded
     * @return a {@link CisMigrationReport} describing the outcome for this dimension
     */
    public static CisMigrationReport migrateWorld(final ServerWorld world) {
        final Path storageDir = resolveStorageDir(world);
        if (!Files.exists(storageDir)) {
            return CisMigrationReport.empty();
        }

        final Identifier dimId = world.getRegistryKey().getValue();
        LOGGER.info("Checking CIS region directory for world {}: {}", dimId, storageDir);

        final CisStorage<?, ?, ?, ?> storage = FabricCisStorageHelper.getStorage(world);
        final CisStorageMigrator<?, ?> migrator = new CisStorageMigrator<>(storage, LOGGER);
        final CisMigrationReport report = migrator.migrateStorage(storageDir);

        if (report.migratedChunks() > 0 || report.failedChunks() > 0) {
            LOGGER.info(
                    "Chunkis CIS Migration complete for world {}. Target v{}, migrated {}, skipped {}, failed {}.",
                    dimId,
                    CisVersionMap.latestVersion(),
                    report.migratedChunks(),
                    report.skippedChunks(),
                    report.failedChunks());
        }

        return report;
    }

    /**
     * Resolves the Chunkis region directory for the given dimension.
     *
     * <p>The overworld uses {@code <save>/chunkis/regions}. All other dimensions
     * use {@code <save>/dimensions/<namespace>/<path>/chunkis/regions}, mirroring
     * Minecraft's own layout for non-overworld level data.
     *
     * @param world the dimension whose storage directory should be resolved
     * @return absolute path to the dimension's Chunkis region directory
     */
    public static Path resolveStorageDir(final ServerWorld world) {
        final Identifier dimId = world.getRegistryKey().getValue();
        final Path root = world.getServer().getSavePath(WorldSavePath.ROOT);
        final Path baseDir = OVERWORLD_PATH.equals(dimId.getPath())
                ? root
                : root.resolve(DIMENSIONS_SUBPATH).resolve(dimId.getNamespace()).resolve(dimId.getPath());
        return baseDir.resolve(CIS_REGION_SUBPATH);
    }
}