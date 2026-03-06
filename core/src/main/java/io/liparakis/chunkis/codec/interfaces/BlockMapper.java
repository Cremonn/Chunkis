package io.liparakis.chunkis.codec.interfaces;

import io.liparakis.chunkis.spi.CisAdapter;

/**
 * Interface for mapping blocks to numerical IDs for serialization.
 *
 * @param <S> BlockState type
 */
public interface BlockMapper<S> extends CisAdapter<S> {

    /**
     * Gets the block ID for a given block state, registering it if necessary.
     *
     * @param state the block state
     * @return the block ID
     */
    int getBlockId(S state);

    /**
     * Flushes new mappings to persistent storage if there are unsaved changes.
     */
    void flush();
}
