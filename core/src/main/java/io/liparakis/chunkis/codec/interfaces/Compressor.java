package io.liparakis.chunkis.codec.interfaces;

/**
 * Interface defining compression and decompression algorithms.
 */
public interface Compressor {
    /**
     * Compresses the given raw data.
     *
     * @param data the raw data
     * @return the compressed data
     */
    byte[] compress(byte[] data);

    /**
     * Decompresses the given data.
     *
     * @param data the compressed data
     * @return the raw data
     * @throws Exception if decompression fails
     */
    byte[] decompress(byte[] data) throws Exception;
}
