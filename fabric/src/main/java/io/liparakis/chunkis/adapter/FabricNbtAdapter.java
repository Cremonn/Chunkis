package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.NbtAdapter;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.jetbrains.annotations.NotNull;

import java.io.*;

/**
 * Streaming NBT adapter that avoids intermediate buffering when possible.
 *
 * <p>
 * Uses direct streaming for {@link DataOutputStream}/{@link DataInputStream},
 * falling back to buffering only when the output/input is a generic
 * {@link DataOutput}/{@link DataInput} (e.g., {@code RandomAccessFile}).
 *
 * <p>
 * <b>Thread safety:</b> Thread-local buffer reuse means no state is shared
 * across threads. No world or chunk references are retained.
 *
 * @author Liparakis
 * @version 1.2
 */
public final class FabricNbtAdapter implements NbtAdapter<NbtCompound> {

    /** Maximum allowed NBT payload size in bytes (2 MiB). */
    private static final int MAX_NBT_SIZE = 2 * 1024 * 1024;

    /** Initial capacity for thread-local byte buffers (8 KiB). */
    private static final int BUFFER_SIZE = 8192;

    /**
     * Thread-local pool of reusable byte buffers.
     * Avoids per-call allocation on the common write/read path. Each thread
     * retains its own {@link BufferHolder} for the lifetime of the thread.
     */
    private static final ThreadLocal<BufferHolder> BUFFER_POOL = ThreadLocal.withInitial(BufferHolder::new);

    /**
     * Writes the given NBT compound to the given output.
     *
     * <p>
     * Dispatches to {@link #writeStreaming} if output is a {@link DataOutputStream},
     * otherwise falls back to {@link #writeBuffered}.
     *
     * @param nbt    the NBT compound to write
     * @param output the target output
     * @throws IOException if the write fails or the NBT exceeds {@link #MAX_NBT_SIZE}
     */
    @Override
    public void write(final NbtCompound nbt, final DataOutput output) throws IOException {
        if (output instanceof DataOutputStream dos) {
            writeStreaming(nbt, dos);
        } else {
            writeBuffered(nbt, output);
        }
    }

    /**
     * Reads an NBT compound from the given input.
     *
     * <p>
     * Validates the declared payload length before any allocation or read.
     * Dispatches to {@link #readStreaming} if input is a {@link DataInputStream},
     * otherwise falls back to {@link #readBuffered}.
     *
     * @param input the source input
     * @return the parsed NBT compound
     * @throws IOException if the read fails or the declared size is invalid
     */
    @Override
    public NbtCompound read(final DataInput input) throws IOException {
        final int length = input.readInt();
        if (length < 0 || length > MAX_NBT_SIZE) {
            throw new IOException("Invalid NBT size: " + length + " (must be 0–" + MAX_NBT_SIZE + ")");
        }
        return input instanceof DataInputStream dis
                ? readStreaming(dis, length)
                : readBuffered(input, length);
    }

    /**
     * Writes NBT to a {@link DataOutputStream} using the thread-local buffer.
     *
     * <p>
     * Compresses to the buffer first to measure the payload size, then writes
     * a length prefix followed by the compressed bytes. This avoids a second
     * compression pass while still enforcing the size limit.
     *
     * @param nbt    the NBT compound to write
     * @param output the target stream
     * @throws IOException if write fails or payload exceeds {@link #MAX_NBT_SIZE}
     */
    private void writeStreaming(final NbtCompound nbt, final DataOutputStream output) throws IOException {
        final BufferHolder holder = BUFFER_POOL.get();
        holder.reset();
        NbtIo.writeCompressed(nbt, holder.buffer);

        final int size = holder.buffer.size();
        if (size > MAX_NBT_SIZE) {
            throw new IOException("NBT data exceeds maximum allowed size: " + size + " bytes (max: " + MAX_NBT_SIZE + ")");
        }

        output.writeInt(size);
        holder.buffer.writeTo(output);
    }

    /**
     * Writes NBT to a generic {@link DataOutput} by materializing the payload
     * as a byte array.
     *
     * <p>
     * Used when the output is not a {@link DataOutputStream} (e.g.,
     * {@code RandomAccessFile}).
     *
     * @param nbt    the NBT compound to write
     * @param output the target output
     * @throws IOException if write fails or payload exceeds {@link #MAX_NBT_SIZE}
     */
    private void writeBuffered(final NbtCompound nbt, final DataOutput output) throws IOException {
        final BufferHolder holder = BUFFER_POOL.get();
        holder.reset();
        NbtIo.writeCompressed(nbt, holder.buffer);

        final byte[] data = holder.buffer.toByteArray();
        if (data.length > MAX_NBT_SIZE) {
            throw new IOException("NBT data exceeds maximum allowed size: " + data.length + " bytes (max: " + MAX_NBT_SIZE + ")");
        }

        output.writeInt(data.length);
        output.write(data);
    }

    /**
     * Reads NBT directly from a {@link DataInputStream} using a
     * {@link BoundedInputStream} to prevent over-reading and maintain stream
     * synchronization.
     *
     * @param input  the source stream
     * @param length the expected byte length of the NBT payload
     * @return the parsed NBT compound
     * @throws IOException if read or decompression fails
     */
    private NbtCompound readStreaming(final DataInputStream input, final int length) throws IOException {
        // BoundedInputStream wraps without closing the underlying stream (caller owns lifecycle).
        try (BoundedInputStream bounded = new BoundedInputStream(input, length)) {
            final NbtCompound nbt = NbtIo.readCompressed(bounded, NbtSizeTracker.of(MAX_NBT_SIZE));
            // Drain any remaining bytes to keep the stream position consistent.
            while (bounded.read() != -1) { /* intentional drain */ }
            return nbt;
        }
    }

    /**
     * Reads NBT from a generic {@link DataInput} by buffering the payload bytes first.
     *
     * @param input  the source input
     * @param length the byte length of the NBT payload to read
     * @return the parsed NBT compound
     * @throws IOException if read or decompression fails
     */
    private NbtCompound readBuffered(final DataInput input, final int length) throws IOException {
        final BufferHolder holder = BUFFER_POOL.get();
        holder.reset();
        final byte[] buffer = holder.getReadBuffer(length);

        input.readFully(buffer, 0, length);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(buffer, 0, length)) {
            return NbtIo.readCompressed(bais, NbtSizeTracker.of(MAX_NBT_SIZE));
        }
    }

    /**
     * Holds reusable write and read buffers for a single thread.
     *
     * <p>
     * The write buffer ({@link #buffer}) is a resettable {@link ByteArrayOutputStream}.
     * The read buffer ({@link #readBuffer}) grows on demand but never shrinks,
     * amortizing allocation cost across repeated calls.
     */
    private static final class BufferHolder {

        /** Reusable write buffer. Always reset before use. */
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(BUFFER_SIZE);

        /** Reusable read buffer. Grows as needed; never shrinks. */
        byte[] readBuffer = new byte[BUFFER_SIZE];

        /** Resets the write buffer to zero size without releasing its backing array. */
        void reset() {
            buffer.reset();
        }

        /**
         * Returns the read buffer, growing it if the requested size exceeds capacity.
         *
         * @param size the minimum required capacity
         * @return a byte array of at least {@code size} bytes
         */
        byte[] getReadBuffer(final int size) {
            if (size > readBuffer.length) {
                readBuffer = new byte[size];
            }
            return readBuffer;
        }
    }

    /**
     * An {@link InputStream} wrapper that reads at most a fixed number of bytes.
     *
     * <p>
     * Used to prevent over-reading when parsing NBT from a shared
     * {@link DataInputStream}, ensuring stream position stays consistent
     * for subsequent reads.
     */
    private static final class BoundedInputStream extends FilterInputStream {

        /** Remaining bytes this stream is permitted to read. */
        private long remaining;

        /**
         * @param in       the underlying stream to wrap
         * @param maxBytes the maximum number of bytes that may be read
         */
        BoundedInputStream(final InputStream in, final long maxBytes) {
            super(in);
            this.remaining = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            final int result = super.read();
            if (result != -1) remaining--;
            return result;
        }

        @Override
        public int read(final byte @NotNull [] b, final int off, final int len) throws IOException {
            if (remaining <= 0) return -1;
            final int toRead = (int) Math.min(len, remaining);
            final int result = super.read(b, off, toRead);
            if (result > 0) remaining -= result;
            return result;
        }

        /**
         * Intentional no-op: the caller owns the underlying {@link DataInputStream}
         * lifecycle and must not have it closed here.
         */
        @Override
        public void close() {
            // Intentional no-op: caller retains ownership of the underlying stream.
        }
    }
}