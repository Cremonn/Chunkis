package io.liparakis.chunkis.codec;

import io.liparakis.chunkis.codec.interfaces.BitReader;
import io.liparakis.chunkis.codec.util.BitUtils;

public class ArrayBitReader implements BitReader {
    private byte[] data;
    private int byteIndex;
    private int bitIndex;
    private int endIndex;

    public ArrayBitReader(byte[] data) {
        setData(data, 0, data.length);
    }

    @Override
    public void setData(byte[] data, int offset, int length) {
        this.data = data;
        this.byteIndex = offset;
        this.bitIndex = 0;
        this.endIndex = offset + length;
    }

    @Override
    public long read(int bits) {
        if (bits == 0)
            return 0;
        long result = 0;

        while (bits > 0) {
            if (byteIndex >= endIndex) {
                return result << bits;
            }

            int b = data[byteIndex] & 0xFF;
            int remaining = 8 - bitIndex;
            int take = Math.min(bits, remaining);
            int chunk = (b >>> (remaining - take)) & ((1 << take) - 1);

            result = (result << take) | chunk;

            bitIndex += take;
            bits -= take;

            if (bitIndex == 8) {
                byteIndex++;
                bitIndex = 0;
            }
        }

        return result;
    }

    @Override
    public int readZigZag(int bits) {
        return BitUtils.decodeZigZag((int) read(bits));
    }
}
