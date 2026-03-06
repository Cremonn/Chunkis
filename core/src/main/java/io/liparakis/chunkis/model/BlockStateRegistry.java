package io.liparakis.chunkis.model;

import it.unimi.dsi.fastutil.objects.Object2ShortOpenHashMap;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;

/**
 * Global registry that assigns stable numeric IDs to all known block states.
 * Populated at world open and persisted across sessions to maintain ID
 * stability.
 * Replaces per-section palettes in dense chunks, cutting allocation and GC
 * overhead.
 *
 * @param <S> The BlockState type
 */
public final class BlockStateRegistry<S> {
    public static final short UNKNOWN_ID = -1;

    private final Object2ShortOpenHashMap<S> stateToId;
    private final ArrayList<S> idToState;
    private final S fallbackState;
    private volatile boolean isFrozen = false;

    /**
     * Creates a new registry.
     *
     * @param fallbackState State returned for orphaned IDs (e.g., removed mods)
     */
    public BlockStateRegistry(S fallbackState) {
        this.stateToId = new Object2ShortOpenHashMap<>();
        this.stateToId.defaultReturnValue(UNKNOWN_ID);
        this.idToState = new ArrayList<>();
        this.fallbackState = fallbackState;
    }

    /**
     * Restores previously persisted ID mappings.
     * Existing IDs are locked in. Unresolvable states (removed mods) are preserved
     * to keep ID alignment, but runtime lookups will yield the fallback state.
     *
     * @param persistedMappings Map of string IDs to their assigned short IDs
     * @param resolver          Function to resolve a string ID back to a live block
     *                          state
     */
    public void restore(Map<String, Short> persistedMappings, Function<String, S> resolver) {
        if (isFrozen)
            throw new IllegalStateException("Registry is frozen");

        // Ensure idToState is large enough for the highest restored ID
        int maxId = -1;
        for (Short id : persistedMappings.values()) {
            if (id > maxId)
                maxId = id;
        }

        idToState.ensureCapacity(maxId + 1);
        while (idToState.size() <= maxId) {
            idToState.add(null); // Initialize with nulls
        }

        for (Map.Entry<String, Short> entry : persistedMappings.entrySet()) {
            short id = entry.getValue();
            S state = resolver.apply(entry.getKey());

            if (state != null) {
                // Mod is present
                stateToId.put(state, id);
                idToState.set(id, state);
            }
        }
    }

    /**
     * Appends any new live states that weren't restored from persistence.
     * Call this after {@link #restore} and pass all active states (vanilla+modded).
     *
     * @param allLiveStates Iterable of all currently valid block states
     */
    public void populate(Iterable<S> allLiveStates) {
        if (isFrozen)
            throw new IllegalStateException("Registry is frozen");

        for (S state : allLiveStates) {
            if (!stateToId.containsKey(state)) {
                short nextId = (short) idToState.size();
                stateToId.put(state, nextId);
                idToState.add(state);
            }
        }
    }

    /**
     * Freezes the registry. No further registrations are permitted for this
     * session.
     */
    public void freeze() {
        this.isFrozen = true;
    }

    /**
     * Gets the stable numeric ID for the given state.
     */
    public short getId(S state) {
        return stateToId.getShort(state);
    }

    /**
     * Gets the state for the numeric ID. If the ID is orphaned (removed mod)
     * or exactly UNKNOWN_ID, returns the fallback state.
     */
    public S getState(short id) {
        if (id < 0 || id >= idToState.size())
            return fallbackState;
        S state = idToState.get((int) id & 0xFFFF);
        if (state == null)
            return fallbackState; // Orphaned ID
        return state;
    }

    /** Gets the number of assigned IDs. */
    public int size() {
        return idToState.size();
    }
}
