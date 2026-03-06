package io.liparakis.chunkis.codec.interfaces;

/**
 * Interface for reading bits from an underlying storage mechanism.
 */
public interface BitReader {
    /**
     * Configures the reader to read from a slice of a byte array.
     * Zero-copy operation - does not copy the array.
     *
     * @param data   the byte array to read from
     * @param offset the starting offset in the array
     * @param length the number of bytes to read
     */
    void setData(byte[] data, int offset, int length);

    /**
     * Reads the specified number of bits and returns them as a long value.
     *
     * @param bits the number of bits to read (0-64)
     * @return the read value as a long
     */
    long read(int bits);

    /**
     * Reads and decodes a ZigZag encoded signed integer.
     *
     * @param bits the number of bits to read
     * @return the decoded signed integer
     */
    int readZigZag(int bits);
}
