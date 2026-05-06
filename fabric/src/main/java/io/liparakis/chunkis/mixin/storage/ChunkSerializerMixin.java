package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.mixin.accessor.ChunkBlockEntityNbtAccessor;
import io.liparakis.chunkis.storage.CisNbtUtil;
import io.liparakis.chunkis.storage.FabricCisStorageHelper;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.GlobalChunkTracker;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
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
 * Intercepts {@link SerializedChunk#convert} to attach Chunkis delta data to
 * freshly converted {@link ProtoChunk} instances.
 *
 * <p>This runs at {@code RETURN}, after vanilla has converted serialized chunk
 * NBT into a proto chunk. Chunkis then loads the matching delta using a
 * memory-first, disk-fallback strategy and attaches it through
 * {@link ChunkisDeltaDuck}.</p>
 *
 * <p>For synthetic empty chunks (no persisted base chunk NBT), the status is
 * reset to {@link ChunkStatus#EMPTY} so vanilla worldgen can regenerate terrain
 * and Chunkis can replay the delta on top of fresh terrain later.</p>
 *
 * <p>For chunks with a persisted vanilla-compatible base chunk NBT, that NBT
 * represents stable generated terrain. Sparse block and block-entity edits are
 * replayed immediately and the status is left intact.</p>
 *
 * @author Liparakis
 * @version 1.2
 *
 */
@Mixin(SerializedChunk.class)
public class ChunkSerializerMixin {


    /**
     * Intercepts vanilla serialized chunk conversion and restores Chunkis state.
     *
     * <p>{@code poiStorage} and {@code key} are unused by Chunkis but are required
     * by the injection signature to match the target method exactly.</p>
     *
     * @param world      server world context
     * @param poiStorage point of interest storage (unused by Chunkis)
     * @param key        storage key (unused by Chunkis)
     * @param chunkPos   chunk position being converted
     * @param cir        callback holding the converted proto chunk
     */
    @Inject(method = "convert", at = @At("RETURN"))
    private static void chunkis$onConvert(
            final ServerWorld world,
            final PointOfInterestStorage poiStorage,
            final StorageKey key,
            final ChunkPos chunkPos,
            final CallbackInfoReturnable<ProtoChunk> cir) {
        final ProtoChunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }
        chunkis$restoreChunkDelta(world, chunkPos, chunk);
    }

    /**
     * Loads, attaches, and applies the Chunkis delta for a converted proto chunk.
     *
     * <p>If no meaningful delta exists the proto chunk is left exactly as vanilla
     * produced it.</p>
     *
     * <p>Restore order:</p>
     * <ol>
     *   <li>Load delta (memory → disk).</li>
     *   <li>Trace log.</li>
     *   <li>Set suppression flag from persisted metadata.</li>
     *   <li>If a base chunk is persisted, replay block and block-entity edits
     *       immediately so player changes survive status-intact loads.</li>
     *   <li>Attach delta to chunk via {@link ChunkisDeltaDuck}.</li>
     *   <li>If no base chunk, reset status to {@link ChunkStatus#EMPTY} so vanilla
     *       worldgen regenerates terrain before delta replay.</li>
     * </ol>
     *
     * @param world the server world
     * @param pos   the chunk position
     * @param chunk the converted proto chunk
     */
    @Unique
    private static void chunkis$restoreChunkDelta(
            final ServerWorld world,
            final ChunkPos pos,
            final ProtoChunk chunk) {
        final ChunkDelta<BlockState, NbtCompound> delta = chunkis$loadDelta(world, pos);


        if (chunkis$isDeltaAbsent(delta)) {
            return;
        }

        delta.setSuppressInitialRepopulation(CisNbtUtil.shouldSuppressInitialRepopulation(delta));

        final boolean hasBase = CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata());

        if (hasBase) {
            chunkis$replayBaseChunkBlockDelta(chunk, delta);
            chunkis$replayBaseChunkBlockEntityDelta(chunk, delta);
        }

        chunkis$attachDeltaToChunk(chunk, delta);

        if (!hasBase) {
            chunk.setStatus(ChunkStatus.EMPTY);
        }
    }

    /**
     * Loads a delta using a memory-first, disk-fallback strategy.
     *
     * <p>The global tracker is checked first because it may hold a newer in-memory
     * state than the on-disk copy. If the tracker has nothing (or only an empty
     * delta), CIS storage is used for cold loads.</p>
     *
     * @param world the server world
     * @param pos   the chunk position
     * @return the loaded delta, or {@code null} if none exists
     */
    @Unique
    private static ChunkDelta<BlockState, NbtCompound> chunkis$loadDelta(
            final ServerWorld world,
            final ChunkPos pos) {
        final ChunkDelta<?, ?> memoryDelta = GlobalChunkTracker.getDelta(world, pos);
        if (!chunkis$isDeltaAbsent(memoryDelta)) {
            return chunkis$castBlockDelta(memoryDelta);
        }
        return chunkis$loadDeltaFromDisk(world, pos);
    }

    /**
     * Loads a delta from persistent CIS storage.
     *
     * <p>{@link CisStorage#load(CisChunkPos)} returns an empty delta when no entry
     * exists; callers should apply {@link #chunkis$isDeltaAbsent} afterward.</p>
     *
     * @param world the server world
     * @param pos   the chunk position
     * @return the loaded disk delta (may be empty)
     */
    @Unique
    private static ChunkDelta<BlockState, NbtCompound> chunkis$loadDeltaFromDisk(
            final ServerWorld world,
            final ChunkPos pos) {
        return FabricCisStorageHelper.getStorage(world)
                .load(new CisChunkPos(pos.x, pos.z));
    }

    /**
     * Attaches {@code delta} to {@code chunk} through {@link ChunkisDeltaDuck}.
     *
     * <p>Stays defensive: if the interface is unexpectedly absent the vanilla
     * conversion path is not broken.</p>
     *
     * @param chunk the proto chunk to mutate
     * @param delta the delta to attach
     */
    @Unique
    private static void chunkis$attachDeltaToChunk(
            final ProtoChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            deltaDuck.chunkis$setDelta(delta);
        }
    }

    /**
     * Replays sparse block edits directly into the chunk sections of a proto chunk
     * loaded from a persisted base chunk.
     *
     * <p>Full-status base chunks may not go through the same regeneration replay
     * path as synthetic empty chunks, so block edits are applied here to prevent
     * restored player changes from being skipped.</p>
     *
     * <p>Writes directly into {@link ChunkSection} instances for speed, bypassing
     * higher-level chunk mutation code. Block-entity NBT and other delta payloads
     * remain on the delta and are handled by the later restore path.</p>
     *
     * @param chunk the proto chunk to mutate
     * @param delta the loaded block delta
     */
    @Unique
    private static void chunkis$replayBaseChunkBlockDelta(
            final ProtoChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final int bottomY = chunk.getBottomY();
        final int topY = chunk.getTopYInclusive();
        final ChunkSection[] sections = chunk.getSectionArray();

        delta.forEachBlock((localX, localY, localZ, state) -> {
            if (state == null || localY < bottomY || localY > topY) {
                return;
            }
            final int sectionIndex = chunk.getSectionIndex(localY);
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return;
            }
            final ChunkSection section = sections[sectionIndex];
            if (section == null) {
                return;
            }
            section.setBlockState(localX, localY & 15, localZ, state);
        });
    }

    /**
     * Installs sparse block-entity NBT from {@code delta} into {@code chunk}'s
     * pending block-entity map.
     *
     * <p>Delta block entities are newer than the base chunk NBT and must win,
     * particularly for inventories modified after the base chunk was captured.</p>
     *
     * @param chunk the proto chunk to mutate
     * @param delta the loaded block/NBT delta
     */
    @Unique
    private static void chunkis$replayBaseChunkBlockEntityDelta(
            final ProtoChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final ChunkPos chunkPos = chunk.getPos();
        final var pendingBlockEntities =
                ((ChunkBlockEntityNbtAccessor) chunk).chunkis$getBlockEntityNbts();

        delta.getBlockEntities().long2ObjectEntrySet().forEach(entry -> {
            final NbtCompound nbt = entry.getValue();
            if (nbt == null) {
                return;
            }
            final long packed = entry.getLongKey();
            final BlockPos worldPos = chunkPos.getBlockPos(
                    BlockInstruction.unpackX(packed),
                    BlockInstruction.unpackY(packed),
                    BlockInstruction.unpackZ(packed)
            );
            pendingBlockEntities.put(worldPos, nbt.copy());
        });
    }

    /**
     * Returns {@code true} when {@code delta} is absent or carries no payload.
     *
     * @param delta the delta to inspect; may be {@code null}
     * @return {@code true} if the delta should be ignored
     */
    @Unique
    private static boolean chunkis$isDeltaAbsent(final ChunkDelta<?, ?> delta) {
        return delta == null || delta.isEmpty();
    }

    /**
     * Casts a wildcard tracker delta to the concrete Minecraft block/NBT shape.
     *
     * <p>The global tracker stores deltas with wildcard generic types because it is
     * shared infrastructure. This mixin works exclusively with
     * {@code ChunkDelta<BlockState, NbtCompound>}, so the unchecked cast is
     * isolated here rather than scattered across the load path.</p>
     *
     * @param delta a wildcard delta from the tracker; must be a block/NBT delta
     * @return the same instance typed as {@code ChunkDelta<BlockState, NbtCompound>}
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> chunkis$castBlockDelta(
            final ChunkDelta<?, ?> delta) {
        return (ChunkDelta<BlockState, NbtCompound>) delta;
    }


}