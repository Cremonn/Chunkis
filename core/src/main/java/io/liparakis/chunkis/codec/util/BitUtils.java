package io.liparakis.chunkis.codec.util;

/**
 * High-performance bit manipulation utilities for storage and networking.
 */
public final class BitUtils {

    private BitUtils() {}

    public static int encodeZigZag(int n) {
        return (n << 1) ^ (n >> 31);
    }

    public static int decodeZigZag(int n) {
        return (n >>> 1) ^ -(n & 1);
    }
}
