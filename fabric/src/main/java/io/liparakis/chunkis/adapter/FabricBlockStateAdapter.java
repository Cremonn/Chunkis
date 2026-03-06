package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.BlockStateAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance Fabric implementation of {@link BlockStateAdapter} with
 * aggressive caching.
 *
 * <p>
 * Optimized for Minecraft's immutable block state system:
 * <ul>
 * <li>Block properties are defined at registration time and never change.</li>
 * <li>Property values are finite and immutable.</li>
 * <li>Block states are queried millions of times per second during chunk
 * operations.</li>
 * </ul>
 *
 * <p>
 * Three caches are maintained indefinitely (block metadata is static):
 * <ul>
 * <li>{@code blockPropertiesCache} — block → property list</li>
 * <li>{@code propertyValuesCache} — property → value list</li>
 * <li>{@code valueIndexCache} — property → (value → index) map for O(1) lookup</li>
 * </ul>
 *
 * <p>
 * <b>Reflection bootstrap:</b> {@code Property.getValues()} is resolved once at
 * class load via {@link #GET_VALUES_METHOD} to handle remapped method names
 * across Minecraft mapping sets (intermediary vs. named). A direct-call fallback
 * is used if reflection fails.
 *
 * <p>
 * <b>Thread safety:</b> All caches use {@link ConcurrentHashMap} and are safe
 * for concurrent access.
 *
 * @author Liparakis
 * @version 1.2
 */
public final class FabricBlockStateAdapter implements BlockStateAdapter<Block, BlockState, Property<?>> {

    // Shared immutable sentinels to avoid allocation for blocks with no properties.
    private static final List<Property<?>> EMPTY_PROPERTIES = Collections.emptyList();
    private static final List<Object>      EMPTY_VALUES     = Collections.emptyList();

    // Caches for immutable block metadata — safe to retain indefinitely since
    // block properties and their values are fixed at registration time.
    private final Map<Block, List<Property<?>>>           blockPropertiesCache = new ConcurrentHashMap<>(256);
    private final Map<Property<?>, List<Object>>          propertyValuesCache  = new ConcurrentHashMap<>(512);
    private final Map<Property<?>, Map<Object, Integer>>  valueIndexCache      = new ConcurrentHashMap<>(512);

    /**
     * Pre-resolved {@code Property.getValues()} method, resolved once at class
     * load. Handles remapped method names across Minecraft mapping sets
     * (e.g., intermediary vs. named) and return type changes (List vs. Collection).
     * Null if resolution fails entirely — a direct-call fallback is used in that case.
     */
    private static final java.lang.reflect.Method GET_VALUES_METHOD = resolveGetValuesMethod();

    /**
     * Attempts to resolve {@code Property.getValues()} by name first, then falls
     * back to scanning all public methods by signature.
     *
     * @return the resolved Method, or null if resolution fails entirely
     */
    private static java.lang.reflect.Method resolveGetValuesMethod() {
        try {
            return Property.class.getMethod("getValues");
        } catch (final NoSuchMethodException e) {
            // Fallback: scan by signature for intermediary/production mappings.
            for (final java.lang.reflect.Method method : Property.class.getMethods()) {
                if (Collection.class.isAssignableFrom(method.getReturnType())
                        && method.getParameterCount() == 0
                        && !method.getReturnType().equals(Class.class)
                        && !method.getReturnType().equals(String.class)
                        && !method.getReturnType().equals(Optional.class)) {
                    return method;
                }
            }
            return null;
        }
    }

    /**
     * Extracts the block from a block state.
     *
     * @param state the block state (must not be null)
     * @return the block
     * @throws NullPointerException if state is null
     */
    @Override
    public Block getBlock(final BlockState state) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        return state.getBlock();
    }

    /**
     * Returns an immutable list of properties for the given block, with caching.
     *
     * <p>
     * Block properties are defined at registration and never change, making them
     * safe to cache indefinitely. Returns a shared empty sentinel for stateless
     * blocks (e.g., stone).
     *
     * @param block the block (must not be null)
     * @return unmodifiable list of properties, never null
     * @throws NullPointerException if block is null
     */
    @Override
    public List<Property<?>> getProperties(final Block block) {
        Objects.requireNonNull(block, "Block cannot be null");
        return blockPropertiesCache.computeIfAbsent(block, b -> {
            final Collection<Property<?>> properties = b.getStateManager().getProperties();
            return properties.isEmpty() ? EMPTY_PROPERTIES : List.copyOf(properties);
        });
    }

    /**
     * Returns the name of a property.
     *
     * @param property the property (must not be null)
     * @return the property name
     * @throws NullPointerException if property is null
     */
    @Override
    public String getPropertyName(final Property<?> property) {
        Objects.requireNonNull(property, "Property cannot be null");
        return property.getName();
    }

    /**
     * Returns an immutable list of possible values for a property, with caching.
     *
     * <p>
     * Uses reflection to handle method signature differences across Minecraft
     * versions, with a direct-call fallback if reflection fails or is unavailable.
     *
     * @param property the property (must not be null)
     * @return unmodifiable list of values, never null
     * @throws NullPointerException if property is null
     */
    @Override
    public List<Object> getPropertyValues(final Property<?> property) {
        Objects.requireNonNull(property, "Property cannot be null");
        return propertyValuesCache.computeIfAbsent(property, p -> {
            final Collection<?> values = safeGetValues(p);
            return values.isEmpty() ? EMPTY_VALUES : List.copyOf(values);
        });
    }

    /**
     * Returns the index of the current value of a property in the block state.
     *
     * <p>
     * Uses a cached O(1) value-to-index map, avoiding linear scans on the hot path.
     *
     * @param state    the block state (must not be null)
     * @param property the property to query (must not be null)
     * @return the index of the current value, or -1 if not found
     * @throws NullPointerException if state or property is null
     */
    @Override
    public int getValueIndex(final BlockState state, final Property<?> property) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        Objects.requireNonNull(property, "Property cannot be null");
        return getOrCreateIndexMap(property).getOrDefault(state.get(property), -1);
    }

    /**
     * Creates a new block state with the specified property value.
     *
     * <p>
     * Uses direct indexed access into the cached values list, avoiding
     * {@code ArrayList} creation and linear {@code indexOf} searches on the hot path.
     *
     * @param state      the original block state (must not be null)
     * @param property   the property to modify (must not be null)
     * @param valueIndex the index of the desired value in the property's value list
     * @return a new block state with the property set, or the original if the index is invalid
     * @throws NullPointerException if state or property is null
     */
    @Override
    public BlockState withProperty(
            final BlockState state,
            final Property<?> property,
            final int valueIndex) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        Objects.requireNonNull(property, "Property cannot be null");

        final List<Object> values = getPropertyValues(property);
        if (valueIndex < 0 || valueIndex >= values.size()) return state;

        return applyPropertyValue(state, property, values.get(valueIndex));
    }

    /**
     * Returns the default state for a block.
     *
     * @param block the block (must not be null)
     * @return the default block state
     * @throws NullPointerException if block is null
     */
    @Override
    public BlockState getDefaultState(final Block block) {
        Objects.requireNonNull(block, "Block cannot be null");
        return block.getDefaultState();
    }

    /**
     * Returns true if the given block state represents air.
     *
     * @param state the block state (must not be null)
     * @return true if the state is air
     * @throws NullPointerException if state is null
     */
    @Override
    public boolean isAir(final BlockState state) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        return state.isAir();
    }

    /**
     * Gets or creates an O(1) value-to-index mapping for the given property.
     *
     * <p>
     * Allows index lookups to avoid linear scans of the values list, which is
     * critical for high-frequency block state queries during chunk processing.
     *
     * <p>
     * Package-private for testing visibility.
     *
     * @param property the property to build or retrieve an index map for
     * @return unmodifiable map of value → index
     */
    Map<Object, Integer> getOrCreateIndexMap(final Property<?> property) {
        return valueIndexCache.computeIfAbsent(property, p -> {
            final List<Object> values = getPropertyValues(p);
            final Map<Object, Integer> indexMap = new HashMap<>(values.size());
            for (int i = 0; i < values.size(); i++) {
                indexMap.put(values.get(i), i);
            }
            return Collections.unmodifiableMap(indexMap);
        });
    }

    /**
     * Invokes {@code property.getValues()} via the resolved reflective method,
     * falling back to a direct call if reflection is unavailable or throws.
     *
     * @param property the property to query
     * @return the raw collection of allowed values
     */
    private static Collection<?> safeGetValues(final Property<?> property) {
        if (GET_VALUES_METHOD != null) {
            try {
                return (Collection<?>) GET_VALUES_METHOD.invoke(property);
            } catch (final Exception ignored) {
                // Fall through to direct call.
            }
        }
        return property.getValues();
    }

    /**
     * Applies the given value to the given property on the given state.
     *
     * <p>
     * The unchecked cast is safe because {@code value} originates from
     * {@code property.getValues()}, guaranteeing type compatibility.
     * The {@code @SuppressWarnings} scope is intentionally kept to this single
     * method to minimize the blast radius of the suppression.
     *
     * @param state    the block state to modify
     * @param property the property to set
     * @param value    the value to apply, sourced from the property's own value list
     * @return a new BlockState with the property applied
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyPropertyValue(
            final BlockState state,
            final Property<?> property,
            final Object value) {
        return state.with((Property) property, (Comparable) value);
    }
}