package io.liparakis.chunkis.mixin.entity;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.util.CisNbtUtil;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Shadow
    private World world;

    @Shadow
    public abstract Vec3d getPos();

    @Shadow
    public abstract BlockPos getBlockPos();

    @Shadow
    public abstract boolean isRemoved();

    @Shadow
    public abstract boolean hasVehicle();

    @Unique
    private Vec3d chunkis$lastCapturePos = null;

    @Unique
    private ChunkPos chunkis$lastCaptureChunk = null;

    /**
     * Checks if the entity is eligible for capture.
     */
    @Unique
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isEligibleForCapture() {
        if (!(world instanceof ServerWorld serverWorld))
            return false;

        if (serverWorld.getServer().getThread() != Thread.currentThread())
            return false;

        Entity self = (Entity) (Object) this;
        if (self.age == 0 && (self.getX() == 0 && self.getY() == 0 && self.getZ() == 0))
            return false;

        if (self instanceof PlayerEntity)
            return false;
        if (isRemoved() || hasVehicle())
            return false;

        BlockPos pos = getBlockPos();
        return serverWorld.isChunkLoaded(ChunkPos.toLong(pos));
    }

    /**
     * Captures the entity's current state to the given chunk's delta.
     */
    @Unique
    @SuppressWarnings("unchecked")
    private void captureToDelta(WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck))
            return;

        ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) deltaDuck.chunkis$getDelta();
        if (delta != null) {
            Entity self = (Entity) (Object) this;
            try (ErrorReporter.Logging logging = new ErrorReporter.Logging(
                    self.getErrorReporterContext(), Chunkis.LOGGER)) {

                final NbtWriteView writeView = NbtWriteView.create(
                        logging,
                        self.getRegistryManager());

                self.writeData(writeView);

                final NbtCompound nbt = writeView.getNbt();
                if (!nbt.isEmpty()) {
                    CisNbtUtil.ensureEntityIdPresent(nbt, self);
                    delta.putEntity(self.getId(), nbt);
                    GlobalChunkTracker.markDirty(chunk);

                    this.chunkis$lastCapturePos = getPos();
                    this.chunkis$lastCaptureChunk = chunk.getPos();
                }
            } catch (Exception e) {
                Chunkis.LOGGER.debug("Chunkis: Skipped proactive capture for entity {} (invalid state)", self.getId());
            }
        }
    }

    /**
     * Removes the entity from the given chunk's delta.
     */
    @Unique
    @SuppressWarnings("unchecked")
    private void removeFromDelta(WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck))
            return;

        ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) deltaDuck.chunkis$getDelta();
        if (delta != null) {
            Entity self = (Entity) (Object) this;
            delta.removeEntity(self.getId());
            GlobalChunkTracker.markDirty(chunk);
        }
    }

    /**
     * Hook into setPos to track significant movement and chunk border crossing.
     */
    @Inject(method = "setPos", at = @At("RETURN"))
    private void chunkis$onSetPos(double x, double y, double z, CallbackInfo ci) {
        if (!isEligibleForCapture())
            return;

        Vec3d currentPos = getPos();
        ChunkPos currentChunk = new ChunkPos(getBlockPos());

        if (chunkis$lastCapturePos == null || chunkis$lastCaptureChunk == null) {
            return;
        }

        boolean chunkChanged = !currentChunk.equals(chunkis$lastCaptureChunk);
        double distSq = currentPos.squaredDistanceTo(chunkis$lastCapturePos);

        if (chunkChanged || distSq > 1.0) {
            ServerWorld serverWorld = (ServerWorld) world;

            if (chunkChanged) {
                WorldChunk oldChunk = serverWorld.getWorldChunk(chunkis$lastCaptureChunk.getBlockPos(0, 0, 0));
                removeFromDelta(oldChunk);
            }

            WorldChunk newChunk = serverWorld.getWorldChunk(currentChunk.getBlockPos(0, 0, 0));
            captureToDelta(newChunk);
        }
    }

    /**
     * Hook into tracked data changes (e.g., health, name)
     */
    @Inject(method = "onTrackedDataSet", at = @At("RETURN"))
    private void chunkis$onTrackedDataSet(TrackedData<?> data, CallbackInfo ci) {
        if (!isEligibleForCapture())
            return;

        ServerWorld serverWorld = (ServerWorld) world;
        WorldChunk chunk = serverWorld.getWorldChunk(getBlockPos());
        captureToDelta(chunk);
    }

    /**
     * Hook into entity removal to properly discard destroyed entities from the
     * chunk delta.
     */
    @Inject(method = "remove", at = @At("HEAD"))
    private void chunkis$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        if (!isEligibleForCapture())
            return;

        if (reason != null && reason.shouldDestroy()) {
            ServerWorld serverWorld = (ServerWorld) world;
            WorldChunk chunk = serverWorld.getWorldChunk(getBlockPos());
            removeFromDelta(chunk);
        }
    }
}