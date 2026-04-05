package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@link SerializedChunk#convert} to restore Chunkis delta data
 * into freshly converted {@link ProtoChunk} instances.
 *
 * <p>
 * Injected at {@code RETURN} so the vanilla conversion path completes first.
 * The delta is loaded via a memory-first, disk-fallback strategy and attached
 * to the proto chunk. The chunk status is then reset to
 * {@link ChunkStatus#EMPTY} so the worldgen pipeline re-runs and applies the
 * delta on top of fresh terrain.
 */
@Mixin(SerializedChunk.class)
public class ChunkSerializerMixin {

    @Inject(method = "convert", at = @At("RETURN"))
    private static void chunkis$onConvert(
            final ServerWorld world,
            final PointOfInterestStorage poiStorage,
            final StorageKey key,
            final ChunkPos expectedPos,
            final CallbackInfoReturnable<ProtoChunk> cir) {

        final ProtoChunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }

        restoreChunkDelta(world, chunk.getPos(), chunk);
    }

    @Unique
    private static void restoreChunkDelta(
            final ServerWorld world,
            final ChunkPos pos,
            final ProtoChunk chunk) {

        final ChunkDelta<?, ?> delta = loadDelta(pos, world);
        if (isDeltaAbsent(delta)) {
            return;
        }

        if (delta.needsMigration()) {
            GlobalChunkTracker.addDelta(world, pos, delta);
        }

        attachDeltaToChunk(chunk, delta);
        resetChunkStatus(chunk);
    }

    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDelta(final ChunkPos pos, final ServerWorld world) {
        final ChunkDelta fromMemory = loadDeltaFromMemory(pos, world);
        if (fromMemory != null) {
            return fromMemory;
        }
        return loadDeltaFromDisk(pos, world);
    }

    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDeltaFromMemory(final ChunkPos pos, final ServerWorld world) {
        final ChunkDelta delta = GlobalChunkTracker.getDelta(world, pos);
        return (delta != null && !delta.isEmpty()) ? delta : null;
    }

    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDeltaFromDisk(final ChunkPos pos, final ServerWorld world) {
        return FabricCisStorageHelper.getStorage(world).load(new CisChunkPos(pos.x, pos.z));
    }

    @Unique
    @SuppressWarnings({ "rawtypes" })
    private static void attachDeltaToChunk(final ProtoChunk chunk, final ChunkDelta delta) {
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            deltaDuck.chunkis$setDelta(delta);
        }
    }

    @Unique
    private static void resetChunkStatus(final ProtoChunk chunk) {
        chunk.setStatus(ChunkStatus.EMPTY);
    }

    @Unique
    private static boolean isDeltaAbsent(final ChunkDelta<?, ?> delta) {
        return delta == null || delta.isEmpty();
    }
}
