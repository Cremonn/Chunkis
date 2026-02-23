package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/**
 * Mixin for the base {@link Chunk} class to provide {@link ChunkDelta}
 * capability to all chunk types.
 *
 * <p>
 * Implements {@link ChunkisDeltaDuck} to attach a per-chunk delta, and
 * overrides {@code needsSaving()} so that a chunk is always considered dirty
 * when its delta has unsaved changes — even if vanilla would report it clean.
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
    // Mixin injection point
    // -----------------------------------------------------------------------

    /**
     * Injected at the return of {@code needsSaving()} to override the result
     * when the Chunkis delta is dirty, even if vanilla considers the chunk clean.
     *
     * <p>
     * This ensures the chunk serializer is always given a chance to flush
     * Chunkis modifications even when vanilla believes there is nothing to save.
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

    // -----------------------------------------------------------------------
    // Guard helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the saving flag should be overridden to {@code true}.
     *
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
}