package io.liparakis.chunkis.codec.interfaces;

/**
 * Interface for writing bits to an underlying storage mechanism.
 */
public interface BitWriter {
    /**
     * Writes up to 64 bits to the stream.
     *
     * @param value the value to write
     * @param bits  the number of bits to write (0-64)
     */
    void write(long value, int bits);

    /**
     * Writes a ZigZag encoded signed integer.
     *
     * @param value the signed integer to encode and write
     * @param bits  the number of bits to write
     */
    void writeZigZag(int value, int bits);

    /**
     * Flushes any partial byte to the buffer, advancing to the next byte boundary.
     */
    void flush();

    /**
     * Resets the writer for reuse.
     */
    void reset();

    /**
     * Returns a copy of the written bytes, sized exactly to the data written.
     *
     * @return a byte array containing all written data
     */
    byte[] toByteArray();
}
