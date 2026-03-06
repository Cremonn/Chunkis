package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.model.ChunkDelta;
import io.liparakis.chunkis.util.CisNbtUtil;
import io.liparakis.chunkis.model.CisChunkPos;
import io.liparakis.chunkis.util.FabricRegionChunkStorageHelper;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.ChunkDataList;
import net.minecraft.world.storage.EntityChunkDataAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link EntityChunkDataAccess#writeChunkData} to capture entity NBT
 * into the Chunkis delta system alongside the vanilla entity storage write.
 *
 * <p>
 * For each eligible entity, the entity is serialized to NBT and added to the
 * chunk's delta. The delta is then registered with {@link GlobalChunkTracker}
 * and immediately flushed to CIS storage.
 *
 * <p>
 * Entities are excluded from capture if they are players, already removed, or
 * are passengers (passengers are serialized with their vehicle).
 *
 * <p>
 * <b>Thread safety:</b> The injection fires on the server thread during chunk
 * save.
 *
 * @author Liparakis
 * @version 1.1
 */
@Mixin(EntityChunkDataAccess.class)
public class EntityChunkDataAccessMixin {

    @Shadow
    @Final
    private net.minecraft.server.world.ServerWorld world;

    // -------------------------------------------------------------------------
    // Injection
    // -------------------------------------------------------------------------

    /**
     * Intercepts the entity chunk write to capture eligible entities into the
     * Chunkis delta for the same chunk position.
     *
     * @param dataList the entity data list being written; provides the chunk
     *                 position
     * @param ci       mixin callback (not cancelled)
     */
    @Inject(method = "writeChunkData(Lnet/minecraft/world/storage/ChunkDataList;)V", at = @At("HEAD"))
    private void chunkis$onWriteEntityData(final ChunkDataList<Entity> dataList, final CallbackInfo ci) {
        final ChunkPos pos = dataList.getChunkPos();
        final ChunkDelta<BlockState, NbtCompound> delta = getOrCreateDelta(pos);

        delta.clearActiveEntities();
        delta.clearPendingEntities();

        dataList.stream().forEach(entity ->
        {
            if (!isEligibleForCapture(entity)) return;
            try (ErrorReporter.Logging logging =
                         new ErrorReporter.Logging(entity.getErrorReporterContext(), Chunkis.LOGGER)) {

                final NbtWriteView writeView = NbtWriteView.create(
                        logging,
                        entity.getRegistryManager());

                entity.writeData(writeView);

                final NbtCompound nbt = writeView.getNbt();
                if (!nbt.isEmpty()) {
                    CisNbtUtil.ensureEntityIdPresent(nbt, entity);
                    delta.putEntity(entity.getId(), nbt);
                }
            } catch (final Exception e) {
                Chunkis.LOGGER.error("Chunkis: Failed to serialize entity in EntityChunkDataAccess for {}", pos, e);
            }
        });

        GlobalChunkTracker.addDelta(pos, delta);
        persistDelta(pos, delta);
    }

    /**
     * Returns true if the given entity should be captured into the delta.
     * Excludes players, removed entities, and passengers.
     *
     * @param entity the entity to evaluate
     * @return true if the entity should be captured
     */
    @Unique
    private static boolean isEligibleForCapture(final Entity entity) {
        return !(entity instanceof PlayerEntity)
                && !entity.isRemoved()
                && !entity.hasVehicle();
    }

    // -------------------------------------------------------------------------
    // Delta access and persistence
    // -------------------------------------------------------------------------

    /**
     * Returns the existing delta for the given position from the tracker, or
     * creates a new empty delta if none is registered.
     *
     * @param pos the chunk position
     * @return a non-null delta for the position
     */
    @Unique
    @SuppressWarnings("unchecked")
    private ChunkDelta<BlockState, NbtCompound> getOrCreateDelta(final ChunkPos pos) {
        final ChunkDelta<?, ?> tracked = GlobalChunkTracker.getDelta(pos);
        if (tracked != null) {
            return (ChunkDelta<BlockState, NbtCompound>) tracked;
        }
        return new ChunkDelta<>();
    }

    /**
     * Saves the given delta to CIS storage, logging an error on failure so a
     * single bad chunk does not abort the broader entity write operation.
     *
     * @param pos   the chunk position
     * @param delta the delta to persist
     */
    @Unique
    private void persistDelta(final ChunkPos pos, final ChunkDelta<BlockState, NbtCompound> delta) {
        try {
            FabricRegionChunkStorageHelper.getStorage(world).save(new CisChunkPos(pos.x, pos.z), delta);
        } catch (final Exception e) {
            Chunkis.LOGGER.error(
                    "Chunkis: Failed to flush chunk delta to disk in EntityChunkDataAccess for {}", pos, e);
        }
    }
}