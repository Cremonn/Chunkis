package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registers and handles the {@code /durability_test} and {@code /durability_test_stop} commands.
 *
 * <p>Rapidly teleports the player between two positions to stress the chunk
 * load/save pipeline and verify fix durability under high-frequency transitions.
 *
 * <p>Requires {@link PermissionLevel#GAMEMASTERS} to execute.
 *
 * @author Liparakis
 * @version 1.0
 */
public final class DurabilityTestCommand {

    private static final AtomicReference<ScheduledExecutorService> executorRef = new AtomicReference<>();

    private DurabilityTestCommand() {
        throw new AssertionError("Utility class");
    }

    /**
     * Registers both commands with the given dispatcher.
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("durability_test")
                        .requires(source -> source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .then(CommandManager.argument("pos1", Vec3ArgumentType.vec3())
                                .then(CommandManager.argument("pos2", Vec3ArgumentType.vec3())
                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1))
                                                .then(CommandManager.argument("delayMs", IntegerArgumentType.integer(1))
                                                        .executes(DurabilityTestCommand::runTest))))));

        dispatcher.register(
                CommandManager.literal("durability_test_stop")
                        .requires(source -> source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .executes(DurabilityTestCommand::stopTest));
    }

    private static int runTest(CommandContext<ServerCommandSource> context) {
        final ServerCommandSource source = context.getSource();
        final ServerPlayerEntity player = source.getPlayer();

        if (player == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }

        final Vec3d pos1 = Vec3ArgumentType.getVec3(context, "pos1");
        final Vec3d pos2 = Vec3ArgumentType.getVec3(context, "pos2");
        final int count = IntegerArgumentType.getInteger(context, "count");
        final int delayMs = Math.max(10, IntegerArgumentType.getInteger(context, "delayMs"));

        // Cancel any active test before starting a new one.
        stopInternal();

        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "Chunkis-Durability-Test");
            t.setDaemon(true);
            return t;
        });
        executorRef.set(executor);

        final AtomicInteger remaining = new AtomicInteger(count);

        source.sendFeedback(
                () -> Text.literal("§6[Chunkis] Starting durability test: " + count + " cycles at " + delayMs + "ms " +
                        "delay"),
                true
        );

        executor.scheduleAtFixedRate(
                () -> {
                    try {
                        final int left = remaining.decrementAndGet();

                        if (left < 0) {
                            shutdownAndClear(executor);
                            // Route feedback back to the server thread.
                            source.getServer().execute(() ->
                                    source.sendFeedback(() -> Text.literal("§a[Chunkis] Durability test complete."),
                                            true)
                            );
                            return;
                        }

                        final ServerWorld world = player.getEntityWorld();
                        final Set<PositionFlag> flags = Set.of();

                        final Vec3d target = (left % 2 == 0) ? pos1 : pos2;
                        source.getServer().execute(() -> {
                            if (!player.isRemoved()) {
                                player.teleport(
                                        world,
                                        target.x,
                                        target.y,
                                        target.z,
                                        flags,
                                        player.getYaw(),
                                        player.getPitch(),
                                        false
                                );
                            }
                        });

                    } catch (Exception e) {
                        shutdownAndClear(executor);
                        source.getServer().execute(() ->
                                source.sendError(Text.literal("§c[Chunkis] Durability test failed: " + e.getMessage()))
                        );
                    }
                }, 0, delayMs, TimeUnit.MILLISECONDS
        );

        return 1;
    }

    private static int stopTest(CommandContext<ServerCommandSource> context) {
        if (stopInternal()) {
            context.getSource().sendFeedback(() -> Text.literal("§c[Chunkis] Durability test stopped."), true);
        } else {
            context.getSource().sendError(Text.literal("No durability test is currently running."));
        }
        return 1;
    }

    /**
     * Shuts down the active executor if one exists.
     *
     * @return {@code true} if an active test was canceled; {@code false} otherwise.
     */
    private static boolean stopInternal() {
        final ScheduledExecutorService executor = executorRef.get();
        if (executor == null) return false;
        shutdownAndClear(executor);
        return true;
    }

    /**
     * Shuts down the given executor immediately and clears {@link #executorRef}
     * only if it still points to the same instance — guarding against a race
     * where a newer test has already replaced the reference.
     */
    private static void shutdownAndClear(ScheduledExecutorService executor) {
        executor.shutdownNow();
        executorRef.compareAndSet(executor, null);
    }
}