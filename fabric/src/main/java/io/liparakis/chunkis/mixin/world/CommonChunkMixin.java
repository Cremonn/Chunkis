package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/**
 * Mixin for the base {@link Chunk} class to provide {@link ChunkDelta}
 * capability to all chunk types.
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(Chunk.class)
public abstract class CommonChunkMixin implements ChunkisDeltaDuck {

    @Unique
    private volatile ChunkDelta<?, ?> chunkis$delta = new ChunkDelta<>();

    // -----------------------------------------------------------------------
    // ChunkisDeltaDuck interface implementation
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public ChunkDelta<?, ?> chunkis$getDelta() {
        return chunkis$delta;
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if {@code delta} is {@code null}
     */
    @Override
    public void chunkis$setDelta(final ChunkDelta<?, ?> delta) {
        this.chunkis$delta = Objects.requireNonNull(delta, "ChunkDelta cannot be null");
    }

    // -----------------------------------------------------------------------
    // Mixin injection points
    // -----------------------------------------------------------------------

    /**
     * Injected at the return of {@code needsSaving()} to override the result
     * when the Chunkis delta is dirty, even if vanilla considers the chunk clean.
     *
     * @param cir the returnable callback; return value is overridden to
     *            {@code true} when the delta reports dirty
     */
    @Inject(method = "needsSaving", at = @At("RETURN"), cancellable = true)
    private void chunkis$onNeedsSaving(final CallbackInfoReturnable<Boolean> cir) {
        if (shouldOverrideSavingFlag(cir.getReturnValueZ())) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Injected at the head of {@code setNeedsSaving(boolean)} to keep the
     * Chunkis delta and {@link GlobalChunkTracker} in sync when vanilla marks
     * a chunk dirty.
     *
     * @param needsSaving {@code true} if the chunk is being marked dirty
     * @param ci          the Mixin {@link CallbackInfo}; unused but required by
     *                    the injection contract
     */
    @Inject(method = "setNeedsSaving", at = @At("HEAD"))
    private void chunkis$onSetNeedsSaving(final boolean needsSaving, final CallbackInfo ci) {
        if (!needsSaving) {
            return;
        }
        this.chunkis$delta.markDirty();
        notifyTrackerIfWorldChunk();
    }

    // -----------------------------------------------------------------------
    // Guard helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the saving flag should be overridden to {@code true}.
     * <p>
     * This is the case when vanilla reports the chunk as clean but the Chunkis
     * delta has unsaved changes.
     *
     * @param currentlySaving the value vanilla {@code needsSaving()} returned
     * @return {@code true} if the return value should be overridden
     */
    @Unique
    private boolean shouldOverrideSavingFlag(final boolean currentlySaving) {
        return !currentlySaving && chunkis$delta.isDirty();
    }

    /**
     * Notifies {@link GlobalChunkTracker} if this chunk instance is a
     * {@link WorldChunk}.
     *
     * <p>
     * The {@code instanceof} pattern match is used rather than a cast
     * on {@code this} directly, because at the {@link Chunk} mixin level
     * {@code this} may be any {@link Chunk} subtype.
     */
    @Unique
    private void notifyTrackerIfWorldChunk() {
        if ((Object) this instanceof WorldChunk worldChunk) {
            GlobalChunkTracker.markDirty(worldChunk);
        }
    }
}