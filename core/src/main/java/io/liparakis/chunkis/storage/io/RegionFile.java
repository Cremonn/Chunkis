package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.core.CisChunkPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;

/**
 * Region file handler for 32x32 chunks.
 * Uses a simple offset/length header for chunk locations.
 *
 * @version 1
 * @author Liparakis
 */
final class RegionFile implements AutoCloseable {
    private static final int REGION_MASK = 31;
    private static final int CHUNKS_PER_REGION = 1024;
    /** Total header size: 1024 chunk entries times 8 bytes per entry. */
    private static final int HEADER_SIZE = 8192;
    /** Size of one header entry: 4 bytes offset and 4 bytes length. */
    private static final int HEADER_ENTRY_SIZE = 8;

    /** Absolute path to the backing region file on disk. */
    private final Path path;
    /** Open channel for all reads, writes, compaction swaps, and header updates. */
    private FileChannel channel;
    final int[] offsets = new int[CHUNKS_PER_REGION]; // Package-private for compactor
    final int[] lengths = new int[CHUNKS_PER_REGION];
    /** Reusable direct buffer for single-entry header writes. */
    private final ByteBuffer headerBuffer = ByteBuffer.allocateDirect(HEADER_ENTRY_SIZE);
    /** Whether the in-memory header state has writes not yet forced to disk. */
    private boolean dirty = false;

    /**
     * Opens or creates a region file.
     *
     * @param dir     the parent directory
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @throws IOException if the file cannot be opened
     */
    RegionFile(Path dir, int regionX, int regionZ) throws IOException {
        this.path = dir.resolve(String.format("r.%d.%d.cis", regionX, regionZ));
        this.channel = openChannel();

        if (channel.size() < HEADER_SIZE) {
            initializeNewRegion();
        } else {
            loadHeader();
        }
    }

    /**
     * Initializes a new region file with an empty header.
     */
    private void initializeNewRegion() throws IOException {
        writeFully(channel, ByteBuffer.allocate(HEADER_SIZE), 0);
    }

    /**
     * Loads the header from an existing region file.
     */
    private void loadHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        readFully(channel, header, 0);
        header.flip();

        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            offsets[i] = header.getInt();
            lengths[i] = header.getInt();
        }
    }

    /**
     * Reads chunk data from the region file.
     *
     * @param pos the chunk position
     * @return the raw bytes, or {@code null} if the chunk is not present
     * @throws IOException if a read error occurs
     */
    synchronized byte[] read(CisChunkPos pos) throws IOException {
        int index = getChunkIndex(pos);

        if (offsets[index] == 0) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.allocate(lengths[index]);
        readFully(channel, buffer, offsets[index]);
        return buffer.array();
    }

    /**
     * Writes chunk data to the region file.
     *
     * @param pos  the chunk position
     * @param data the data to write, or {@code null} to clear the chunk
     * @throws IOException if a write error occurs
     */
    synchronized void write(CisChunkPos pos, byte[] data) throws IOException {
        int index = getChunkIndex(pos);
        int dataLength = (data == null) ? 0 : data.length;

        int offset = calculateWriteOffset(index, dataLength);

        if (dataLength > 0) {
            writeFully(channel, ByteBuffer.wrap(data), offset);
        }

        updateHeader(index, offset, dataLength);
        dirty = true;
    }

    /**
     * Calculates the offset where data should be written.
     * Reuses existing space if it fits, otherwise appends to the end.
     *
     * @param index      the chunk index
     * @param dataLength the length of the data to be written
     * @return the file offset
     * @throws IOException if a file error occurs
     */
    private int calculateWriteOffset(int index, int dataLength) throws IOException {
        if (dataLength <= lengths[index] && offsets[index] != 0) {
            return offsets[index];
        }
        return (int) channel.size();
    }

    /**
     * Updates the header entry for a chunk in memory and on disk.
     *
     * @param index  the chunk index
     * @param offset the new offset
     * @param length the new length
     * @throws IOException if a write error occurs
     */
    private void updateHeader(int index, int offset, int length) throws IOException {
        offsets[index] = (length == 0) ? 0 : offset;
        lengths[index] = length;

        headerBuffer.clear();
        headerBuffer.putInt(offsets[index]);
        headerBuffer.putInt(lengths[index]);
        headerBuffer.flip();

        writeFully(channel, headerBuffer, (long) index * HEADER_ENTRY_SIZE);
    }

    /**
     * Gets the index of a chunk within the region file header (0-1023).
     *
     * @param pos the chunk position
     * @return the header index
     */
    private static int getChunkIndex(CisChunkPos pos) {
        return (pos.x() & REGION_MASK) + (pos.z() & REGION_MASK) * 32;
    }

    /**
     * Flushes pending writes to disk.
     */
    void flush() {
        if (dirty) {
            try {
                if (channel.isOpen()) {
                    channel.force(false);
                }
                dirty = false;
            } catch (IOException e) {
                io.liparakis.chunkis.Chunkis.LOGGER.warn("Failed to flush region file", e);
            }
        }
    }

    /**
     * Compacts the region file by rewriting it contiguously.
     * This operation is synchronized to prevent concurrent writes.
     */
    synchronized void compact() {
        try {
            flush();
            Path tempPath = writeCompactedTempFile();
            swapCompactedFile(tempPath);
        } catch (IOException e) {
            io.liparakis.chunkis.Chunkis.LOGGER.error("Failed to compact region {}", path, e);
            recoverChannel();
        }
    }

    /**
     * Writes a compacted replacement file beside the current region and returns its path.
     */
    private Path writeCompactedTempFile() throws IOException {
        Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");

        try (FileChannel dest = FileChannel.open(tempPath, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writeFully(dest, ByteBuffer.allocate(HEADER_SIZE), 0);
            ByteBuffer newHeader = writeLiveChunks(dest);
            writeFully(dest, newHeader.flip(), 0);
            dest.force(true);
        }

        return tempPath;
    }

    /**
     * Copies all live chunks into the compacted file and builds the replacement header in memory.
     */
    private ByteBuffer writeLiveChunks(FileChannel dest) {
        int currentOffset = HEADER_SIZE;
        ByteBuffer newHeader = ByteBuffer.allocate(HEADER_SIZE);
        ByteBuffer chunkData = ByteBuffer.allocate(maxLiveChunkLength());

        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            if (offsets[i] != 0 && lengths[i] > 0) {
                currentOffset = copyLiveChunk(dest, newHeader, chunkData, i, currentOffset);
            } else {
                writeEmptyHeaderEntry(newHeader);
            }
        }

        return newHeader;
    }

    /**
     * Copies one live chunk payload into the compacted file and appends its new header entry.
     */
    private int copyLiveChunk(
            FileChannel dest,
            ByteBuffer newHeader,
            ByteBuffer chunkData,
            int index,
            int currentOffset) {
        try {
            chunkData.clear();
            chunkData.limit(lengths[index]);
            readFully(channel, chunkData, offsets[index]);
            chunkData.flip();
            writeFully(dest, chunkData, currentOffset);

            newHeader.putInt(currentOffset);
            newHeader.putInt(lengths[index]);
            return currentOffset + lengths[index];
        } catch (IOException e) {
            writeEmptyHeaderEntry(newHeader);
            return currentOffset;
        }
    }

    /**
     * Returns the largest currently referenced chunk payload size in the region.
     */
    private int maxLiveChunkLength() {
        int maxLength = 0;
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            if (offsets[i] != 0 && lengths[i] > maxLength) {
                maxLength = lengths[i];
            }
        }
        return maxLength;
    }

    /**
     * Appends an empty chunk entry to the replacement header.
     */
    private static void writeEmptyHeaderEntry(ByteBuffer header) {
        header.putInt(0);
        header.putInt(0);
    }

    /**
     * Replaces the live region file with the compacted temp file and reloads the header state.
     */
    private void swapCompactedFile(Path tempPath) throws IOException {
        channel.close();
        Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        channel = openChannel();
        loadHeader();
    }

    /**
     * Attempts to reopen the backing channel after a failed compaction swap.
     */
    private void recoverChannel() {
        if (!channel.isOpen()) {
            try {
                channel = openChannel();
            } catch (IOException ex) {
                io.liparakis.chunkis.Chunkis.LOGGER
                        .error("CRITICAL: Failed to reopen region after failed compaction", ex);
            }
        }
    }

    /**
     * Opens the backing region file channel with read/write/create semantics.
     */
    private FileChannel openChannel() throws IOException {
        return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE);
    }

    /**
     * Reads exactly {@code target.remaining()} bytes or fails with EOF.
     */
    private static void readFully(FileChannel source, ByteBuffer target, long position) throws IOException {
        long currentPosition = position;
        while (target.hasRemaining()) {
            int bytesRead = source.read(target, currentPosition);
            if (bytesRead < 0) {
                throw new IOException("Unexpected EOF while reading region file");
            }
            currentPosition += bytesRead;
        }
    }

    /**
     * Writes the full contents of {@code source}, retrying until no bytes remain.
     */
    private static void writeFully(FileChannel target, ByteBuffer source, long position) throws IOException {
        long currentPosition = position;
        while (source.hasRemaining()) {
            int bytesWritten = target.write(source, currentPosition);
            if (bytesWritten <= 0) {
                throw new IOException("Failed to make progress while writing region file");
            }
            currentPosition += bytesWritten;
        }
    }

    /**
     * Closes the region file.
     */
    @Override
    public void close() {
        flush();
        try {
            channel.close();
        } catch (IOException e) {
            io.liparakis.chunkis.Chunkis.LOGGER.warn("Failed to close region file", e);
        }
    }
}
