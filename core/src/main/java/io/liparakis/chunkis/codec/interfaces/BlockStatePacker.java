package io.liparakis.chunkis.codec.interfaces;

/**
 * Interface for property bit-packing and unpacking.
 *
 * @param <B> Block type
 * @param <S> BlockState type
 */
public interface BlockStatePacker<B, S> {

    /**
     * Marker interface for property metadata used by packers.
     */
    interface PackerMeta {
    }

    /**
     * Gets or creates property metadata for a block.
     *
     * @param block The block to get metadata for
     * @return Cached property metadata array
     */
    PackerMeta[] getPropertyMetas(B block);

    /**
     * Writes all property values of a BlockState to the BitWriter.
     *
     * @param writer The bit writer
     * @param state  The block state
     * @param metas  Pre-computed property metadata
     */
    void writeProperties(BitWriter writer, S state, PackerMeta[] metas);

    /**
     * Reads property values from BitReader and reconstructs the BlockState.
     *
     * @param reader The bit reader
     * @param block  The block type
     * @param metas  Pre-computed property metadata
     * @return Reconstructed block state
     */
    S readProperties(BitReader reader, B block, PackerMeta[] metas);
}
