package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.storage.model.CisConstants;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Thread-local compression context to avoid allocations and synchronization.
 *
 * @version 1
 * @author Liparakis
 */
final class CompressionContext {
    private static final int COMPRESSION_BUFFER_SIZE = 8192;
    private static final int UNCOMPRESSED_LEVEL = Deflater.NO_COMPRESSION;
    private static final int COMPRESSED_LEVEL = CisConstants.COMPRESSION_LEVEL;

    /** Reused DEFLATE compressor configured to the core CIS compression level. */
    private final Deflater deflater;
    /** Reused INFLATE decompressor for chunk payload reads. */
    private final Inflater inflater;
    /** Shared transfer buffer for both compression and decompression loops. */
    private final byte[] buffer = new byte[COMPRESSION_BUFFER_SIZE];
    /** Shared byte sink reused to avoid per-call output buffer allocation. */
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(COMPRESSION_BUFFER_SIZE);
    /** Last compression level applied to {@link #deflater}. */
    private int currentCompressionLevel = COMPRESSED_LEVEL;

    CompressionContext() {
        this(new Deflater(CisConstants.COMPRESSION_LEVEL), new Inflater());
    }

    // Visible for testing
    CompressionContext(Deflater deflater, Inflater inflater) {
        this.deflater = deflater;
        this.inflater = inflater;
    }

    /**
     * Compresses data using DEFLATE.
     *
     * @param data the raw data
     * @return the compressed data
     */
    byte[] compress(byte[] data) {
        updateCompressionLevel(compressionLevelFor(data.length));
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();
        outputStream.reset();

        while (!deflater.finished()) {
            int bytesCompressed = deflater.deflate(buffer);
            outputStream.write(buffer, 0, bytesCompressed);
        }

        return outputStream.toByteArray();
    }

    /**
     * Returns the DEFLATE level used for a raw payload of {@code inputLength}.
     *
     * <p>
     * Small payloads are still written as valid DEFLATE streams, but without
     * compression work. This preserves backward-compatible reads while avoiding
     * server-thread CPU on tiny incremental saves.
     */
    static int compressionLevelFor(final int inputLength) {
        return inputLength <= CisConstants.UNCOMPRESSED_DELTA_THRESHOLD
                ? UNCOMPRESSED_LEVEL
                : COMPRESSED_LEVEL;
    }

    private void updateCompressionLevel(final int compressionLevel) {
        if (currentCompressionLevel == compressionLevel) {
            return;
        }

        deflater.setLevel(compressionLevel);
        currentCompressionLevel = compressionLevel;
    }

    /**
     * Decompresses data using INFLATE.
     *
     * @param data the compressed data
     * @return the raw data
     * @throws Exception if inflation fails
     */
    byte[] decompress(byte[] data) throws Exception {
        inflater.reset();
        inflater.setInput(data);
        outputStream.reset();

        while (!inflater.finished()) {
            int bytesDecompressed = inflater.inflate(buffer);
            if (bytesDecompressed == 0) {
                if (inflater.needsInput()) {
                    break;
                }
                if (inflater.needsDictionary()) {
                    throw new java.util.zip.ZipException("Decompression requires a dictionary");
                }
                // If we get here: bytesDecompressed == 0, not finished, not needing input, not
                // needing dictionary.
                // This means the inflater is stalled (potentially corrupted data or infinite
                // loop condition).
                throw new java.util.zip.ZipException("Decompression stalled");
            }
            outputStream.write(buffer, 0, bytesDecompressed);
        }

        return outputStream.toByteArray();
    }
}
