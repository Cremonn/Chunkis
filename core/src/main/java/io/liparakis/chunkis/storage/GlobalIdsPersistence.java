package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.model.BlockStateRegistry;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles persistence for the Global Block State IDs.
 * Reads and writes "global_ids.json" to maintain ID stability across world sessions.
 */
public final class GlobalIdsPersistence {
    private GlobalIdsPersistence() {}

    /**
     * Loads the persisted IDs and initializes the registry.
     * If the file doesn't exist, it populates it with all current live states.
     * If the file exists, it restores previous IDs and appends any newly loaded mods.
     * 
     * @param registryFile Path to global_ids.json
     * @param allLiveStates Iterable of all registered block states in the live game
     * @param registryAdapter Adapter to convert a block state to/from a String ID
     * @param fallbackState Fallback state for removed mods (typically Air)
     * @return A fully populated and frozen BlockStateRegistry
     * @param <S> The BlockState type
     */
    public static <S> BlockStateRegistry<S> loadOrInitRegistry(
            Path registryFile,
            Iterable<S> allLiveStates,
            BlockRegistryAdapter<S> registryAdapter,
            S fallbackState) throws IOException {
            
        BlockStateRegistry<S> registry = new BlockStateRegistry<>(fallbackState);

        if (Files.exists(registryFile)) {
            // Restore existing IDs to keep them stable
            Map<String, Short> persisted = readJson(registryFile);
            registry.restore(persisted, registryAdapter::getBlock);
            
            // Append any new states (from added mods)
            registry.populate(allLiveStates);
            
            // Re-save if new states were added
            if (persisted.size() < registry.size()) {
                writeJson(registryFile, registry, registryAdapter);
            }
        } else {
            // First time world open - assign fresh sequential IDs
            registry.populate(allLiveStates);
            writeJson(registryFile, registry, registryAdapter);
        }

        registry.freeze();
        return registry;
    }

    // ================= Minimal JSON Parser for Flat Map =================
    // Format: { "0": "minecraft:air", "1": "minecraft:stone" }
    
    private static Map<String, Short> readJson(Path file) throws IOException {
        Map<String, Short> map = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.equals("{") || line.equals("}")) continue;
                
                // Parse lines like: "0": "minecraft:air",
                int colon = line.indexOf(':');
                if (colon == -1) continue;
                
                String numPart = line.substring(0, colon).trim();
                String valPart = line.substring(colon + 1).trim();
                
                // Strip quotes and commas
                numPart = unquote(numPart);
                valPart = unquote(valPart);
                if (valPart.endsWith(",")) valPart = valPart.substring(0, valPart.length() - 1);
                valPart = unquote(valPart); // Remove trailing comma then unquote again if needed
                
                try {
                    short id = Short.parseShort(numPart);
                    map.put(valPart, id); // Map string -> short ID
                } catch (NumberFormatException ignored) {
                    // Skip invalid lines
                }
            }
        }
        return map;
    }

    private static <S> void writeJson(Path file, BlockStateRegistry<S> registry, BlockRegistryAdapter<S> adapter) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("{\n");
            
            int maxId = registry.size() - 1;
            boolean first = true;
            
            for (short i = 0; i <= maxId; i++) {
                S state = registry.getState(i);
                
                // For orphaned IDs where the mod was removed, we don't serialize them as the fallback state,
                // we'd optimally keep their original string. But since the registry currently forgets the
                // original string if the mod was removed, they just get skipped or serialized as fallback.
                // To keep IDs stable without shifting on the next load, we just write the fallback for now.
                String strId = adapter.getId(state);
                if (strId != null) {
                    if (!first) {
                        writer.write(",\n");
                    }
                    first = false;
                    writer.write("  \"" + i + "\": \"" + strId + "\"");
                }
            }
            
            writer.write("\n}\n");
        }
    }

    private static String unquote(String str) {
        if (str.length() >= 2 && str.startsWith("\"") && str.endsWith("\"")) {
            return str.substring(1, str.length() - 1);
        }
        return str;
    }
}
