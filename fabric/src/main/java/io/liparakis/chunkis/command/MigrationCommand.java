package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command to handle bulk migration of CIS data from older versions (CIS7)
 * to the latest format (CIS8).
 *
 * <p>
 * Migration is performed on a dedicated background thread to avoid blocking
 * the server main thread. Progress and results are reported back via feedback
 * messages to the command source.
 *
 * <p>
 * Requires permission level 2 (operator).
 *
 * @author Liparakis
 * @version 1.1
 */
public final class MigrationCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationCommand.class);

    /** Matches region file names of the form {@code r.<x>.<z>.cis}. */
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cis");

    /** Minimum permission level required to run the migration command. */
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    /** Number of chunks per region axis (32×32 = 1024 chunks per region file). */
    private static final int REGION_SIZE = 32;

    /** Name of the dedicated migration background thread. */
    private static final String MIGRATION_THREAD_NAME = "Chunkis-Migration-Thread";

    private MigrationCommand() {
        throw new AssertionError("Utility class");
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers the {@code /chunkis migrate} command with the given dispatcher.
     *
     * @param dispatcher the server command dispatcher
     */
    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("chunkis")
                        .requires(MigrationCommand::hasPermission)
                        .then(CommandManager.literal("migrate")
                                .executes(MigrationCommand::runMigration)));
    }

    // -------------------------------------------------------------------------
    // Permission
    // -------------------------------------------------------------------------

    /**
     * Version-independent permission check.
     *
     * <p>
     * Tries {@code source.hasPermissionLevel(level)} first. If that throws
     * (e.g., due to a mapping mismatch), falls back to
     * {@code PlayerManager.isOperator(GameProfile)}, which is stable across
     * Minecraft 1.21.x. Returns {@code true} as a last resort to prevent
     * crashing the server on permission check failure.
     *
     * @param source the command source to check
     * @return true if the source meets the required level
     */
    private static boolean hasPermission(final ServerCommandSource source) {
        try {
            return source.hasPermissionLevel(MigrationCommand.REQUIRED_PERMISSION_LEVEL);
        } catch (final Throwable primary) {
            return hasPermissionFallback(source);
        }
    }

    /**
     * Fallback permission check using
     * {@code PlayerManager.isOperator(GameProfile)}.
     * Returns {@code true} if all fallback paths also fail, to avoid a server
     * crash.
     *
     * @param source the command source to check
     * @return true if the player is an operator, or true if the check itself fails
     */
    private static boolean hasPermissionFallback(final ServerCommandSource source) {
        try {
            final var server = source.getServer();
            if (server == null)
                return true;

            final var player = source.getPlayer();
            if (player == null)
                return true;

            // isOperator(GameProfile) is stable across 1.21.x PlayerManager
            return server.getPlayerManager().isOperator(player.getGameProfile());
        } catch (final Throwable fallbackFailure) {
            // Return true to prevent crashing the server if permission check is broken
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Command entry point
    // -------------------------------------------------------------------------

    /**
     * Entry point for {@code /chunkis migrate}. Sends an initial feedback message
     * and dispatches migration work to a dedicated background thread.
     *
     * @param context the command context
     * @return 1 (command accepted)
     */
    private static int runMigration(final CommandContext<ServerCommandSource> context) {
        final ServerCommandSource source = context.getSource();
        source.sendFeedback(() -> Text.literal("§6[Chunkis] Starting bulk migration from CIS7 to CIS8..."), true);

        final Thread migrationThread = new Thread(
                () -> executeMigration(source),
                MIGRATION_THREAD_NAME);
        migrationThread.start();

        return 1;
    }

    /**
     * Executes the full migration across all server worlds on the background
     * thread.
     * Reports completion or failure back to the command source.
     *
     * @param source the command source to report results to
     */
    private static void executeMigration(final ServerCommandSource source) {
        try {
            final int totalMigrated = migrateAllWorlds(source);
            source.sendFeedback(
                    () -> Text
                            .literal("§a[Chunkis] Migration complete! Upgraded " + totalMigrated + " chunks to CIS8."),
                    true);
        } catch (final Exception e) {
            LOGGER.error("Migration failed", e);
            source.sendFeedback(
                    () -> Text.literal("§c[Chunkis] Migration failed! Check server logs for details."),
                    true);
        }
    }

    // -------------------------------------------------------------------------
    // World-level migration
    // -------------------------------------------------------------------------

    /**
     * Iterates all server worlds and migrates each one.
     *
     * @param source the command source for progress feedback
     * @return total number of chunks migrated across all worlds
     */
    private static int migrateAllWorlds(final ServerCommandSource source) {
        int total = 0;
        for (final ServerWorld world : source.getServer().getWorlds()) {
            total += migrateWorld(world, source);
        }
        return total;
    }

    /**
     * Migrates all CIS7 region files found in the given world's storage directory.
     *
     * @param world  the world to migrate
     * @param source the command source for progress feedback
     * @return number of chunks migrated in this world
     */
    private static int migrateWorld(final ServerWorld world, final ServerCommandSource source) {
        final String dimId = world.getRegistryKey().getValue().toString();
        source.sendFeedback(() -> Text.literal("§7Migrating dimension: " + dimId + "..."), false);

        final Path storageDir = resolveStorageDir(world);
        if (!Files.exists(storageDir))
            return 0;

        final CisStorage<?, ?, ?, ?> storage = FabricCisStorageHelper.getStorage(world);
        return migrateRegionFiles(storage, storageDir, dimId);
    }

    /**
     * Scans the given storage directory for region files and migrates each one.
     *
     * @param storage    the storage instance to load/save deltas through
     * @param storageDir the directory to scan for {@code r.*.*.cis} files
     * @param dimId      the dimension identifier (used only for error logging)
     * @return number of chunks migrated across all region files in the directory
     */
    private static int migrateRegionFiles(
            final CisStorage<?, ?, ?, ?> storage,
            final Path storageDir,
            final String dimId) {

        int migrated = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, "r.*.*.cis")) {
            for (final Path path : stream) {
                migrated += migrateRegionFileIfMatched(storage, path);
            }
        } catch (final IOException e) {
            LOGGER.error("Failed to iterate storage directory for {}", dimId, e);
        }

        return migrated;
    }

    /**
     * Parses the region file name and delegates to {@link #migrateRegion} if the
     * file name matches the expected pattern.
     *
     * @param storage the storage instance
     * @param path    the candidate region file path
     * @return number of chunks migrated from this file, or 0 if name did not match
     */
    private static int migrateRegionFileIfMatched(
            final CisStorage<?, ?, ?, ?> storage,
            final Path path) {

        final Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches())
            return 0;

        final int rx = Integer.parseInt(matcher.group(1));
        final int rz = Integer.parseInt(matcher.group(2));
        return migrateRegion(storage, rx, rz);
    }

    // -------------------------------------------------------------------------
    // Region-level migration
    // -------------------------------------------------------------------------

    /**
     * Iterates all {@value #REGION_SIZE}×{@value #REGION_SIZE} chunk positions
     * within the given region and migrates any chunk that needs it.
     *
     * @param storage the storage instance to load/save deltas through
     * @param rx      region X coordinate
     * @param rz      region Z coordinate
     * @return number of chunks migrated in this region
     */
    private static int migrateRegion(final CisStorage<?, ?, ?, ?> storage, final int rx, final int rz) {
        int migrated = 0;
        for (int x = 0; x < REGION_SIZE; x++) {
            for (int z = 0; z < REGION_SIZE; z++) {
                if (migrateChunk(storage, rx, rz, x, z)) {
                    migrated++;
                }
            }
        }
        return migrated;
    }

    /**
     * Loads the delta for a single chunk position and re-saves it if migration is
     * needed.
     *
     * <p>
     * The unchecked raw-type cast on {@code storage.save()} is unavoidable here
     * because
     * {@link CisStorage} is parameterized and the wildcard-captured types cannot be
     * threaded through without changing the public API. The save is safe because
     * the
     * delta originated from the same storage instance.
     *
     * @param storage the storage instance
     * @param rx      region X coordinate
     * @param rz      region Z coordinate
     * @param x       local chunk X within the region (0–31)
     * @param z       local chunk Z within the region (0–31)
     * @return true if this chunk was migrated and saved
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static boolean migrateChunk(
            final CisStorage<?, ?, ?, ?> storage,
            final int rx,
            final int rz,
            final int x,
            final int z) {

        final CisChunkPos pos = new CisChunkPos((rx << 5) + x, (rz << 5) + z);
        final ChunkDelta<?, ?> delta = storage.load(pos);

        if (!requiresMigration(delta))
            return false;

        ((CisStorage) storage).save(pos, delta);
        return true;
    }

    // -------------------------------------------------------------------------
    // Guard predicates
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given delta is non-null, non-empty, and flagged for
     * migration.
     *
     * @param delta the delta to evaluate, may be null
     * @return true if the delta should be re-saved in the new format
     */
    private static boolean requiresMigration(final ChunkDelta<?, ?> delta) {
        return delta != null && !delta.isEmpty() && delta.needsMigration();
    }

    // -------------------------------------------------------------------------
    // Path resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves the CIS region storage directory for the given world.
     *
     * <p>
     * For the overworld, resolves to {@code <save>/chunkis/regions}.
     * For other dimensions, resolves to
     * {@code <save>/dimensions/<namespace>/<path>/chunkis/regions}.
     *
     * @param world the world whose storage directory to resolve
     * @return the absolute path to the region storage directory
     */
    private static Path resolveStorageDir(final ServerWorld world) {
        final String dimPath = world.getRegistryKey().getValue().getPath();
        Path baseDir = world.getServer().getSavePath(WorldSavePath.ROOT);

        if (!isOverworld(dimPath)) {
            final String namespace = world.getRegistryKey().getValue().getNamespace();
            baseDir = baseDir.resolve("dimensions").resolve(namespace).resolve(dimPath);
        }

        return baseDir.resolve("chunkis").resolve("regions");
    }

    /**
     * Returns true if the given dimension path corresponds to the overworld.
     *
     * @param dimPath the dimension registry path (e.g., "overworld", "the_nether")
     * @return true if dimPath is "overworld"
     */
    private static boolean isOverworld(final String dimPath) {
        return "overworld".equals(dimPath);
    }
}