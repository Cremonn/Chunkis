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
 * Fabric implementation of BlockRegistryAdapter with performance optimizations.
 * This adapter provides bidirectional mapping between blocks and their string
 * identifiers with caching to reduce memory allocation and improve lookup
 * performance.
 *
 * <p>
 * Thread-safe for concurrent access.
 *
 * @author Liparakis
 * @version 1.1
 */
public final class FabricBlockRegistryAdapter implements BlockRegistryAdapter<Block> {

    // Bidirectional caches for fast lookups.
    // ConcurrentHashMap provides thread-safety without synchronized overhead.
    private final Map<Block, String> blockToIdCache = new ConcurrentHashMap<>(256);
    private final Map<String, Identifier> stringToIdentifierCache = new ConcurrentHashMap<>(256);
    private final Map<String, Block> idToBlockCache = new ConcurrentHashMap<>(256);

    // Pre-interned constants for the most common case.
    private static final Block AIR_BLOCK = Blocks.AIR;
    private static final String AIR_ID = "minecraft:air";

    /**
     * Maximum cache size to prevent unbounded memory growth.
     * Minecraft typically has fewer than 1000 blocks, so 1024 provides headroom
     * for modded environments without allowing unbounded growth.
     */
    private static final int MAX_CACHE_SIZE = 1024;

    // -------------------------------------------------------------------------
    // BlockRegistryAdapter API
    // -------------------------------------------------------------------------

    /**
     * Retrieves the string identifier for a given block with caching.
     *
     * @param block the block to identify
     * @return the string identifier (e.g., "minecraft:stone")
     * @throws NullPointerException if block is null
     */
    @Override
    public String getId(final Block block) {
        Objects.requireNonNull(block, "Block cannot be null");
        if (isAirBlock(block))
            return AIR_ID;
        return blockToIdCache.computeIfAbsent(block, this::resolveBlockId);
    }

    /**
     * Retrieves a block from its string identifier with caching and validation.
     *
     * @param id the string identifier (e.g., "minecraft:stone")
     * @return the corresponding block, or AIR if the identifier is invalid
     */
    @Override
    public Block getBlock(final String id) {
        if (isInvalidId(id))
            return AIR_BLOCK;
        if (isAirId(id))
            return AIR_BLOCK;
        return idToBlockCache.computeIfAbsent(id, this::parseAndRetrieveBlock);
    }

    /**
     * Returns the AIR block constant.
     *
     * @return the AIR block
     */
    @Override
    public Block getAir() {
        return AIR_BLOCK;
    }

    // -------------------------------------------------------------------------
    // Resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves and interns the registry identifier string for a given block.
     * Called only on cache miss; result is stored by the caller.
     *
     * @param block the block to resolve
     * @return interned registry identifier string
     */
    private String resolveBlockId(final Block block) {
        evictIfNeeded(blockToIdCache);
        // Intern string to reduce memory footprint for duplicate IDs
        return Registries.BLOCK.getId(block).toString().intern();
    }

    /**
     * Parses the given identifier string and retrieves the corresponding block
     * from the registry. Called only on cache miss.
     *
     * @param id the string identifier to look up
     * @return the resolved Block
     */
    private Block parseAndRetrieveBlock(final String id) {
        evictIfNeeded(idToBlockCache);
        return Registries.BLOCK.get(resolveIdentifier(id));
    }

    /**
     * Retrieves or creates a cached {@link Identifier} for the given string.
     *
     * @param id the raw identifier string
     * @return the parsed or cached Identifier
     */
    private Identifier resolveIdentifier(final String id) {
        return stringToIdentifierCache.computeIfAbsent(id, this::parseIdentifier);
    }

    /**
     * Attempts to parse the given string into an {@link Identifier},
     * falling back to the AIR identifier if parsing fails.
     *
     * @param id the raw identifier string to parse
     * @return a valid Identifier, never null
     */
    private Identifier parseIdentifier(final String id) {
        evictIfNeeded(stringToIdentifierCache);
        final Identifier parsed = Identifier.tryParse(id);
        // Fallback to AIR if parsing fails, preventing crashes on malformed input
        return parsed != null ? parsed : fallbackIdentifier();
    }

    /**
     * Returns the AIR identifier used as a safe fallback when parsing fails.
     *
     * @return the minecraft:air Identifier
     */
    private static Identifier fallbackIdentifier() {
        return Identifier.of("minecraft", "air");
    }

    // -------------------------------------------------------------------------
    // Guard predicates
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given block is the AIR singleton.
     * Uses identity comparison since AIR_BLOCK is a registry constant.
     */
    private static boolean isAirBlock(final Block block) {
        return block == AIR_BLOCK;
    }

    /**
     * Returns true if the given id is null or empty.
     * Defensive guard to avoid NullPointerException or empty registry lookups.
     */
    private static boolean isInvalidId(final String id) {
        return id == null || id.isEmpty();
    }

    /**
     * Returns true if the given id is the well-known AIR identifier string.
     * Allows fast-path return without a cache or registry lookup.
     */
    private static boolean isAirId(final String id) {
        return AIR_ID.equals(id);
    }

    // -------------------------------------------------------------------------
    // Cache management
    // -------------------------------------------------------------------------

    /**
     * Evicts (clears) the given cache if it has exceeded {@link #MAX_CACHE_SIZE}.
     *
     * <p>
     * This uses a naive clear-all strategy. For production, consider
     * replacing with Caffeine or Guava's LoadingCache for LRU eviction.
     *
     * @param cache the map to check and potentially clear
     */
    private static void evictIfNeeded(final Map<?, ?> cache) {
        if (isCacheOverCapacity(cache)) {
            cache.clear();
        }
    }

    /**
     * Returns true if the cache has exceeded the maximum allowed size.
     *
     * @param cache the map to check
     * @return true if cache.size() exceeds MAX_CACHE_SIZE
     */
    private static boolean isCacheOverCapacity(final Map<?, ?> cache) {
        return cache.size() > MAX_CACHE_SIZE;
    }
}