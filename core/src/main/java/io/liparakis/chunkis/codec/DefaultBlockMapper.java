package io.liparakis.chunkis.codec;

import io.liparakis.chunkis.codec.interfaces.BitReader;
import io.liparakis.chunkis.codec.interfaces.BitWriter;
import io.liparakis.chunkis.codec.interfaces.BlockMapper;
import io.liparakis.chunkis.codec.interfaces.BlockStatePacker;
import io.liparakis.chunkis.model.BlockStateRegistry;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.storage.GlobalIdsPersistence;

import java.io.IOException;

/**
 * Optimized mapping system for block translation with lossless property-based
 * serialization.
 * Uses identity-based comparison for maximum performance and ensures
 * deterministic bitstream generation.
 * Thread-safe with read-write locking for concurrent access.
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 */
public record DefaultBlockMapper<B, S, P>(BlockStateRegistry<B> globalRegistry, BlockStateAdapter<B, S, P> stateAdapter,
                                          BlockStatePacker<B, S> packer) implements BlockMapper<S> {

    /**
     * Creates a new DefaultBlockMapper that delegates to a pre-populated
     * BlockStateRegistry.
     *
     * @param globalRegistry the fully populated block state registry
     * @param stateAdapter   the block state adapter
     * @param packer         the property packer instance
     */
    public DefaultBlockMapper {
    }

    /**
     * Gets the global block ID for a given block state.
     * Always returns the mapped ID, resolving orphans automatically via fallback.
     *
     * @param state the block state
     * @return the block ID
     */
    @Override
    public int getBlockId(S state) {
        B block = stateAdapter.getBlock(state);
        return globalRegistry.getId(block);
    }

    /**
     * No-op. The BlockStateRegistry is pre-populated and saved atomically via
     * {@link GlobalIdsPersistence} so mapping
     * files do not need dynamic flushing.
     */
    @Override
    public void flush() {
        // No-op
    }

    /**
     * Writes all property values of a BlockState to the BitWriter.
     * Each property uses the minimum bits required for its value range.
     *
     * @param writer the BitWriter to write to
     * @param state  the BlockState to serialize
     */
    @Override
    public void writeStateProperties(BitWriter writer, S state) {
        B block = stateAdapter.getBlock(state);
        BlockStatePacker.PackerMeta[] metas = packer.getPropertyMetas(block);
        packer.writeProperties(writer, state, metas);
    }

    /**
     * Reads property values from BitReader and reconstructs the BlockState.
     *
     * @param reader  the BitReader to read from
     * @param blockId the global block ID
     * @return the reconstructed BlockState
     * @throws IOException if the block ID is unknown
     */
    @Override
    public S readStateProperties(BitReader reader, int blockId) throws IOException {
        B block = globalRegistry.getState((short) blockId);

        if (block == null) {
            throw new IOException("Unknown Block ID " + blockId + " - stream desync detected");
        }

        BlockStatePacker.PackerMeta[] metas = packer.getPropertyMetas(block);
        return packer.readProperties(reader, block, metas);
    }
}
