package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.dimension.PortalForcer;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Ensures Chunkis-restored nether portals are visible to vanilla portal lookup.
 *
 * <p>Vanilla portal lookup is POI based. Chunkis restores POIs when a chunk is
 * loaded, but vanilla may query the destination POI area before those chunks have
 * been loaded through Chunkis. This mixin forces only the vanilla portal search
 * area to load before lookup, preserving the existing save/storage lifecycle.</p>
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {

    /**
     * Cached predicate for nether portal POI lookups.
     * Allocated once — the lambda captures nothing and is safe to share.
     */
    @Unique
    private static final Predicate<RegistryEntry<PointOfInterestType>> PORTAL_POI_PREDICATE =
            type -> type.matchesKey(PointOfInterestTypes.NETHER_PORTAL);

    @Shadow
    @Final
    private ServerWorld world;

    /**
     * Loads the same chunk area vanilla is about to search for portal POIs.
     *
     * <p>Before-count and chunk loading are merged into a single traversal
     * to avoid iterating the chunk grid twice.</p>
     *
     * @param pos          destination-scaled portal search origin
     * @param destIsNether true when vanilla will use the smaller Nether search radius
     * @param worldBorder  destination world border
     * @param cir          callback info
     */
    @Inject(method = "getPortalPos", at = @At("HEAD"))
    private void chunkis$loadPortalSearchChunks(
            final BlockPos pos,
            final boolean destIsNether,
            final WorldBorder worldBorder,
            final CallbackInfoReturnable<Optional<?>> cir) {

        final int radius = portalSearchRadius(destIsNether);
        final SearchChunkRange range = SearchChunkRange.of(pos, radius);
        final PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();

        // Single pass: sample POI counts before loading, then force-load each chunk.
        long before = 0;
        for (int chunkX = range.minChunkX(); chunkX <= range.maxChunkX(); chunkX++) {
            for (int chunkZ = range.minChunkZ(); chunkZ <= range.maxChunkZ(); chunkZ++) {
                before += poiStorage.getInChunk(
                        PORTAL_POI_PREDICATE,
                        new ChunkPos(chunkX, chunkZ),
                        PointOfInterestStorage.OccupationStatus.ANY).count();
                world.getChunk(chunkX, chunkZ);
            }
        }

        final long after = countPortalPois(range, poiStorage);

        if (Chunkis.LOGGER.isDebugEnabled() && (before > 0 || after > 0)) {
            Chunkis.LOGGER.debug(
                    "Chunkis [PORTAL]: Prepared {} portal search around {} in {}: loaded {} chunk(s), POIs {} -> {}",
                    destIsNether ? "Nether" : "Overworld",
                    pos,
                    world.getRegistryKey().getValue(),
                    range.chunkCount(),
                    before,
                    after);
        }
    }

    /**
     * Logs whether vanilla found an existing portal or will fall through to portal
     * creation.
     *
     * @param pos          destination-scaled portal search origin
     * @param destIsNether true when vanilla used the smaller Nether search radius
     * @param worldBorder  destination world border
     * @param cir          callback info containing vanilla's lookup result
     */
    @Inject(method = "getPortalPos", at = @At("RETURN"))
    private void chunkis$logPortalLookupResult(
            final BlockPos pos,
            final boolean destIsNether,
            final WorldBorder worldBorder,
            final CallbackInfoReturnable<Optional<?>> cir) {

        final SearchChunkRange range = SearchChunkRange.of(pos, portalSearchRadius(destIsNether));
        final long candidates = countPortalPois(range, world.getPointOfInterestStorage());

        if (cir.getReturnValue().isPresent()) {
            if (Chunkis.LOGGER.isDebugEnabled()) {
                Chunkis.LOGGER.debug(
                    "Chunkis [PORTAL]: Lookup in {} from {} found existing portal with {} candidate POI(s)",
                    world.getRegistryKey().getValue(),
                    pos,
                    candidates);
            }
        } else if (candidates > 0) {
            Chunkis.LOGGER.warn(
                    "Chunkis [PORTAL]: Lookup in {} from {} found no portal despite {} candidate POI(s)",
                    world.getRegistryKey().getValue(),
                    pos,
                    candidates);
        } else if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug(
                    "Chunkis [PORTAL]: Lookup in {} from {} found no portal candidate",
                    world.getRegistryKey().getValue(),
                    pos);
        }
    }

    /**
     * Returns vanilla's portal search radius for the destination dimension.
     *
     * @param destIsNether whether the destination is Nether-like
     * @return portal search radius in blocks
     */
    @Unique
    private static int portalSearchRadius(final boolean destIsNether) {
        return destIsNether ? 16 : 128;
    }

    /**
     * Counts nether portal POIs across all chunks in the given range.
     *
     * <p>Uses the shared {@link #PORTAL_POI_PREDICATE} to avoid per-call
     * lambda allocation, and a local {@code long} accumulator to avoid the
     * {@code long[1]} heap allocation that a lambda closure would require.</p>
     *
     * @param range      chunk range to scan
     * @param poiStorage POI storage for the target world
     * @return total portal POI count across the range
     */
    @Unique
    private static long countPortalPois(final SearchChunkRange range, final PointOfInterestStorage poiStorage) {
        long count = 0;
        for (int chunkX = range.minChunkX(); chunkX <= range.maxChunkX(); chunkX++) {
            for (int chunkZ = range.minChunkZ(); chunkZ <= range.maxChunkZ(); chunkZ++) {
                count += poiStorage.getInChunk(
                        PORTAL_POI_PREDICATE,
                        new ChunkPos(chunkX, chunkZ),
                        PointOfInterestStorage.OccupationStatus.ANY).count();
            }
        }
        return count;
    }

    /**
     * Inclusive chunk bounds for a block-radius portal search square.
     */
    @Unique
    private record SearchChunkRange(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {

        /**
         * Converts a block-space portal search into chunk-space bounds.
         *
         * @param center block-space search center
         * @param radius block-space search radius
         * @return inclusive chunk bounds intersecting the search square
         */
        static SearchChunkRange of(final BlockPos center, final int radius) {
            return new SearchChunkRange(
                    (center.getX() - radius) >> 4,
                    (center.getX() + radius) >> 4,
                    (center.getZ() - radius) >> 4,
                    (center.getZ() + radius) >> 4);
        }

        /**
         * Returns how many chunks are covered by this inclusive range.
         *
         * @return chunk count
         */
        int chunkCount() {
            return (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        }
    }
}
