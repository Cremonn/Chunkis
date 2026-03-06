package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.model.ChunkDelta;
import io.liparakis.chunkis.util.CisNbtUtil;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.util.math.ChunkPos;

@Mixin(ServerWorld.class)
public class ServerWorldMixin {

    @SuppressWarnings("unchecked")
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void chunkis$onEntityAdded(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof PlayerEntity || entity.isRemoved() || entity.hasVehicle())
            return;

        ServerWorld world = (ServerWorld) (Object) this;
        assert world.getServer() != null;
        if (world.getServer().getThread() != Thread.currentThread())
            return;

        if (!world.isChunkLoaded(ChunkPos.toLong(entity.getBlockPos()))) {
            return;
        }

        WorldChunk chunk = world.getWorldChunk(entity.getBlockPos());
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) deltaDuck
                    .chunkis$getDelta();
            if (delta != null) {
                try (ErrorReporter.Logging logging = new ErrorReporter.Logging(
                        entity.getErrorReporterContext(), Chunkis.LOGGER)) {

                    final NbtWriteView writeView = NbtWriteView.create(
                            logging,
                            entity.getRegistryManager());

                    entity.writeData(writeView);

                    final NbtCompound nbt = writeView.getNbt();
                    if (!nbt.isEmpty()) {
                        CisNbtUtil.ensureEntityIdPresent(nbt, entity);
                        delta.putEntity(entity.getId(), nbt);
                        GlobalChunkTracker.markDirty(chunk);
                    }
                } catch (Exception e) {
                    Chunkis.LOGGER.error("Chunkis: Failed to proactively capture added entity {}", entity.getId(), e);
                }
            }
        }
    }
}
