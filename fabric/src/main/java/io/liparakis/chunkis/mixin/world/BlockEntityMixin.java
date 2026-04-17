package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.util.ChunkBlockEntityCapture;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link BlockEntity} that intercepts {@code markDirty()} calls to
 * integrate with the Chunkis delta-tracking and global chunk-tracking systems.
 *
 * <p>
 * When a block entity marks itself dirty on a {@link ServerWorld}, this mixin:
 * <ol>
 * <li>Resolves the {@link WorldChunk} containing the block entity.</li>
 * <li>If the chunk implements {@link ChunkisDeltaDuck}, marks the chunk's
 * {@link ChunkDelta} dirty, flags the chunk for saving, and proactively
 * captures the block entity's current NBT state.</li>
 * <li>Unconditionally notifies {@link GlobalChunkTracker} that the chunk is
 * dirty.</li>
 * </ol>
 *
 * <p>
 * <strong>Chunk lifecycle safety:</strong> No chunk references, world
 * references,
 * or registry lookups are stored beyond the scope of the inject method. All
 * operations
 * are synchronous and execute on the server tick thread.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Shadow
    protected World world;

    @Shadow
    public abstract BlockPos getPos();

    // -----------------------------------------------------------------------
    // Mixin entry point
    // -----------------------------------------------------------------------

    /**
     * Injected at the head of {@link BlockEntity#markDirty()} to trigger
     * Chunkis delta and global dirty tracking.
     *
     * <p>
     * Guards are applied eagerly via early returns to keep nesting flat.
     * The chunk reference is resolved fresh from the world each invocation and
     * is never retained beyond this call.
     *
     * @param ci the Mixin {@link CallbackInfo}; unused but required by the
     *           injection contract
     */
    @Inject(method = "markDirty()V", at = @At("HEAD"))
    private void chunkis$onMarkDirty(final CallbackInfo ci) {
        if (!isInServerWorld()) {
            return;
        }
        final ServerWorld serverWorld = (ServerWorld) world;
        final WorldChunk chunk = serverWorld.getWorldChunk(getPos());
        if (chunk == null) {
            return;
        }
        handleChunkDelta(chunk, serverWorld);
        GlobalChunkTracker.markDirty(chunk);
    }

    // -----------------------------------------------------------------------
    // Guard helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if this block entity's world is a {@link ServerWorld}.
     *
     * <p>
     * The {@code instanceof} check implicitly handles the {@code null} case —
     * a {@code null} world never satisfies {@code instanceof}, so no separate
     * null check is needed here.
     *
     * @return {@code true} if {@link #world} is a non-null {@link ServerWorld}
     */
    @Unique
    private boolean isInServerWorld() {
        // instanceof handles the null case implicitly — a null world never
        // satisfies instanceof, so no separate null check is needed here
        return world instanceof ServerWorld;
    }

    // -----------------------------------------------------------------------
    // Delta handling
    // -----------------------------------------------------------------------

    /**
     * Handles Chunkis delta tracking for the given chunk, if applicable.
     *
     * <p>
     * If the chunk does not implement {@link ChunkisDeltaDuck}, this method
     * returns immediately without side effects. Otherwise it marks the chunk's
     * delta dirty and proactively captures the current block entity NBT state.
     *
     * @param chunk       the {@link WorldChunk} containing this block entity;
     *                    must be non-null
     * @param serverWorld the {@link ServerWorld} the chunk belongs to;
     *                    used to resolve the registry manager for NBT capture
     */
    @Unique
    private void handleChunkDelta(final WorldChunk chunk, final ServerWorld serverWorld) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }
        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        markChunkDirty(chunk, delta);
        captureBlockEntityNbt(serverWorld, delta);
    }

    /**
     * Marks both the {@link ChunkDelta} and the {@link WorldChunk} as dirty.
     *
     * <p>
     * These two calls are semantically coupled: together they represent
     * "this chunk has unsaved changes that must be persisted". They are
     * extracted together to make that coupling explicit and named.
     *
     * @param chunk the {@link WorldChunk} to flag for saving
     * @param delta the {@link ChunkDelta} to mark dirty
     */
    @Unique
    private void markChunkDirty(final WorldChunk chunk, final ChunkDelta<?, ?> delta) {
        // Both calls are semantically coupled: together they represent
        // "this chunk has unsaved changes that must be persisted"
        delta.markDirty();
        chunk.markNeedsSaving();
    }

    /**
     * Proactively captures the current NBT state of this block entity into the
     * provided {@link ChunkDelta}.
     *
     * <p>
     * Capture failures are logged as errors but never rethrown, to avoid
     * disrupting the vanilla {@code markDirty()} call that triggered this inject.
     * The registry manager is resolved fresh from the server world on each call
     * and is never retained.
     *
     * <p>
     * <strong>Unchecked cast note:</strong> The cast to
     * {@code ChunkDelta<BlockState, NbtCompound>} is unavoidable due to type
     * erasure on the wildcard returned by
     * {@link ChunkisDeltaDuck#chunkis$getDelta()}.
     * The {@link SuppressWarnings} annotation is scoped to this method alone to
     * contain the suppression to the narrowest possible scope.
     *
     * @param serverWorld the {@link ServerWorld} whose registry manager is used
     *                    for NBT serialisation; never retained beyond this call
     * @param delta       the {@link ChunkDelta} to capture the block entity into
     */
    @Unique
    @SuppressWarnings({ "unchecked", "ConstantConditions" })
    private void captureBlockEntityNbt(
            final ServerWorld serverWorld,
            final ChunkDelta<?, ?> delta) {
        // The unchecked cast to ChunkDelta<BlockState, NbtCompound> is
        // unavoidable due to type erasure on the wildcard returned by
        // chunkis$getDelta(). It is isolated here to contain the suppression
        // to the narrowest possible scope.
        try {
            ChunkBlockEntityCapture.captureBlockEntity(
                    (BlockEntity) (Object) this, // standard Mixin self-cast pattern
                    serverWorld.getRegistryManager(),
                    (ChunkDelta<BlockState, NbtCompound>) delta);
        } catch (final Exception e) {
            Chunkis.LOGGER.error(
                    "Chunkis: Failed to proactively capture block entity NBT at {}",
                    getPos(), e);
        }
    }
}