package io.liparakis.chunkis.codec;

import io.liparakis.chunkis.codec.interfaces.BitWriter;
import io.liparakis.chunkis.codec.util.BitUtils;

import java.util.Arrays;

public class ArrayBitWriter implements BitWriter {
    private byte[] buffer;
    private int index;
    private int bitIndex;

    public ArrayBitWriter(int initialCapacity) {
        this.buffer = new byte[initialCapacity];
    }

    @Override
    public void reset() {
        this.index = 0;
        this.bitIndex = 0;
    }

    private void ensureCapacity(int bytesNeeded) {
        int required = index + bytesNeeded;
        if (required >= buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length << 1, required + 128));
        }
    }

    @Override
    public void write(long value, int bits) {
        if (bits == 0)
            return;
        ensureCapacity((bits >>> 3) + 2);

        if (bits != 64) {
            value &= (1L << bits) - 1;
        }

        while (bits > 0) {
            int space = 8 - bitIndex;
            int take = Math.min(bits, space);
            long chunk = (value >>> (bits - take)) & ((1L << take) - 1);

            if (bitIndex == 0) {
                buffer[index] = (byte) (chunk << (space - take));
            } else {
                buffer[index] |= (byte) (chunk << (space - take));
            }

            bitIndex += take;
            bits -= take;

            if (bitIndex == 8) {
                index++;
                bitIndex = 0;
            }
        }
    }

    @Override
    public void writeZigZag(int value, int bits) {
        write(BitUtils.encodeZigZag(value), bits);
    }

    @Override
    public void flush() {
        if (bitIndex > 0) {
            index++;
            bitIndex = 0;
        }
    }

    @Override
    public byte[] toByteArray() {
        int length = bitIndex > 0 ? index + 1 : index;
        return Arrays.copyOf(buffer, length);
    }
}
