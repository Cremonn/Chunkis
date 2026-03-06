package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric implementation of {@link BlockRegistryAdapter} with bidirectional
 * caching for reduced allocation and faster registry lookups.
 *
 * <p>
 * Three caches are maintained:
 * <ul>
 * <li>{@code blockToIdCache} — {@link Block} → registry ID string</li>
 * <li>{@code idToBlockCache} — ID string → {@link Block}</li>
 * <li>{@code stringToIdentifierCache} — raw string → parsed {@link Identifier}</li>
 * </ul>
 *
 * <p>
 * All caches are bounded by {@value #MAX_CACHE_SIZE} entries. Eviction uses a
 * naive clear-all strategy; Minecraft typically registers fewer than 1 000
 * blocks, so eviction should not occur in practice.
 *
 * <p>
 * <b>Thread safety:</b> All caches use {@link ConcurrentHashMap} and are safe
 * for concurrent access.
 *
 * @author Liparakis
 * @version 1.2
 */
public final class FabricBlockRegistryAdapter implements BlockRegistryAdapter<Block> {

    private static final Block  AIR_BLOCK = Blocks.AIR;
    private static final String AIR_ID    = "minecraft:air";

    /**
     * Fallback {@link Identifier} returned when {@link Identifier#tryParse} fails
     * on malformed input. Defined as a constant to avoid per-call allocation.
     */
    private static final Identifier AIR_IDENTIFIER = Identifier.of("minecraft", "air");

    /**
     * Maximum cache size. Eviction clears the entire cache when exceeded.
     * Sized with headroom for heavily modded environments.
     */
    private static final int MAX_CACHE_SIZE = 1024;

    private final Map<Block, String>      blockToIdCache         = new ConcurrentHashMap<>(256);
    private final Map<String, Identifier> stringToIdentifierCache = new ConcurrentHashMap<>(256);
    private final Map<String, Block>      idToBlockCache         = new ConcurrentHashMap<>(256);

    /**
     * Returns the registry ID string for the given block, with caching.
     *
     * <p>
     * Air is handled via a fast-path constant to avoid a cache or registry lookup.
     *
     * @param block the block to identify (must not be null)
     * @return the registry ID string (e.g., {@code "minecraft:stone"})
     * @throws NullPointerException if block is null
     */
    @Override
    public String getId(final Block block) {
        Objects.requireNonNull(block, "Block cannot be null");
        if (block == AIR_BLOCK) return AIR_ID;
        return blockToIdCache.computeIfAbsent(block, b -> {
            evictIfNeeded(blockToIdCache);
            // Intern to reduce memory footprint for duplicate ID strings.
            return Registries.BLOCK.getId(b).toString().intern();
        });
    }

    /**
     * Returns the block for the given registry ID string, with caching.
     *
     * <p>
     * Null, empty, and the air ID string are all handled via fast-path constants.
     * Malformed identifiers fall back to {@link Blocks#AIR} via
     * {@link Identifier#tryParse}.
     *
     * @param id the registry ID string (e.g., {@code "minecraft:stone"})
     * @return the corresponding block, or {@link Blocks#AIR} if the ID is invalid
     */
    @Override
    public Block getBlock(final String id) {
        if (id == null || id.isEmpty()) return AIR_BLOCK;
        if (AIR_ID.equals(id)) return AIR_BLOCK;
        return idToBlockCache.computeIfAbsent(id, s -> {
            evictIfNeeded(idToBlockCache);
            final Identifier identifier = stringToIdentifierCache.computeIfAbsent(s, raw -> {
                evictIfNeeded(stringToIdentifierCache);
                final Identifier parsed = Identifier.tryParse(raw);
                return parsed != null ? parsed : AIR_IDENTIFIER;
            });
            return Registries.BLOCK.get(identifier);
        });
    }

    /**
     * Returns the AIR block constant.
     *
     * @return {@link Blocks#AIR}
     */
    @Override
    public Block getAir() {
        return AIR_BLOCK;
    }

    /**
     * Clears the given cache entirely if it has exceeded {@link #MAX_CACHE_SIZE}.
     *
     * <p>
     * Uses a naive clear-all strategy. In practice eviction should not trigger
     * since vanilla + modded Minecraft registers well under 1 000 blocks.
     *
     * @param cache the map to check and potentially clear
     */
    private static void evictIfNeeded(final Map<?, ?> cache) {
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
    }
}