package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.liparakis.chunkis.migrator.CisMigrationReport;
import io.liparakis.chunkis.migrator.CisVersionMap;
import io.liparakis.chunkis.util.CisWorldMigrator;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

/**
 * Registers and handles the {@code /chunkis migrate} command.
 *
 * <p>Upgrades existing CIS storage from older format versions to the current one
 * using the dedicated {@code cismigrator} project. MCA migration (vanilla → CIS)
 * is handled separately and is unaffected by this command.
 *
 * <p><b>Threading:</b> Command dispatch occurs on the server main thread.
 * All migration work is offloaded to a dedicated background thread
 * ({@value #MIGRATION_THREAD_NAME}) to avoid blocking the game loop.
 * No world state is mutated off-thread; only CIS storage files are accessed.
 */
public final class MigrationCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationCommand.class);

    /** Minimum operator permission level required to execute the migration command. */
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    /** Name used for the background migration thread, visible in thread dumps. */
    private static final String MIGRATION_THREAD_NAME = "Chunkis-CIS-Migration-Thread";

    private MigrationCommand() {
        throw new AssertionError("Utility class");
    }

    /**
     * Registers {@code /chunkis migrate} with the given dispatcher.
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
            return server.getPlayerManager().isOperator(player.getPlayerConfigEntry());
        } catch (final Throwable fallbackFailure) {
            // Return true to prevent crashing the server if permission check is broken
            return true;
        }
    }

    /**
     * Entry point for the {@code /chunkis migrate} command.
     *
     * <p>Notifies the invoker of the target version and spawns the background
     * migration thread. Returns immediately so the main thread is not blocked.
     *
     * @param context the command execution context
     * @return {@code 1} on successful dispatch
     */
    private static int runMigration(final CommandContext<ServerCommandSource> context) {
        final ServerCommandSource source = context.getSource();
        source.sendFeedback(
                () -> Text.literal("§6[Chunkis] Starting CIS migration to v" + CisVersionMap.latestVersion() + "..."),
                true);

        new Thread(() -> executeMigration(source), MIGRATION_THREAD_NAME).start();

        return 1;
    }

    /**
     * Runs the full multi-world CIS migration and reports aggregate results.
     *
     * <p><b>Threading:</b> Must only be called from the background migration thread,
     * never from the server main thread.
     *
     * @param source the command source used for feedback messages
     */
    private static void executeMigration(final ServerCommandSource source) {
        try {
            CisMigrationReport total = CisMigrationReport.empty();

            for (final ServerWorld world : source.getServer().getWorlds()) {
                total = merge(total, migrateWorld(world, source));
            }

            final CisMigrationReport finalTotal = total;
            source.sendFeedback(
                    () -> Text.literal("§a[Chunkis] CIS migration complete. Migrated "
                            + finalTotal.migratedChunks()
                            + " chunk(s), skipped "
                            + finalTotal.skippedChunks()
                            + ", failed "
                            + finalTotal.failedChunks()
                            + "."),
                    true);

        } catch (final Exception e) {
            LOGGER.error("CIS migration failed", e);
            source.sendFeedback(
                    () -> Text.literal("§c[Chunkis] CIS migration failed. Check server logs for details."),
                    true);
        }
    }

    /**
     * Migrates CIS storage for a single world dimension and reports per-world results.
     *
     * <p>If the CIS storage directory for the world does not exist, an empty report
     * is returned immediately without performing any I/O beyond the existence check.
     *
     * <p><b>Threading:</b> Called from the background migration thread.
     *
     * @param world  the dimension to migrate
     * @param source the command source used for per-dimension feedback messages
     * @return a {@link CisMigrationReport} describing the outcome for this dimension
     */
    private static CisMigrationReport migrateWorld(final ServerWorld world, final ServerCommandSource source) {
        final String dimId = world.getRegistryKey().getValue().toString();
        source.sendFeedback(() -> Text.literal("§7Migrating CIS storage for " + dimId + "..."), false);

        if (!Files.exists(CisWorldMigrator.resolveStorageDir(world))) {
            return CisMigrationReport.empty();
        }

        final CisMigrationReport report = CisWorldMigrator.migrateWorld(world);

        source.sendFeedback(
                () -> Text.literal("§8" + dimId + ": migrated " + report.migratedChunks()
                        + ", skipped " + report.skippedChunks()
                        + ", failed " + report.failedChunks()),
                false);

        return report;
    }

    /**
     * Combines two {@link CisMigrationReport} instances by summing all counters.
     *
     * @param left  the accumulator report
     * @param right the report to merge into {@code left}
     * @return a new {@link CisMigrationReport} with aggregated counts
     */
    private static CisMigrationReport merge(final CisMigrationReport left, final CisMigrationReport right) {
        return new CisMigrationReport(
                left.scannedChunks() + right.scannedChunks(),
                left.migratedChunks() + right.migratedChunks(),
                left.skippedChunks() + right.skippedChunks(),
                left.failedChunks() + right.failedChunks());
    }
}