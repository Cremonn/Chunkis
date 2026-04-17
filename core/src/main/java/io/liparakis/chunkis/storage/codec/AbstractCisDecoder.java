package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.BitUtils.BitReader;
import io.liparakis.chunkis.storage.CisConstants;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for CIS (Chunk Incremental Storage) decoders containing shared
 * decoding logic.
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 */
public abstract class AbstractCisDecoder<S, N> {

    /** Size of the CIS file header in bytes (magic number + version). */
    protected static final int HEADER_SIZE = 8;

    /** Number of bits in a nibble (half-byte). */
    protected static final int BITS_PER_NIBBLE = 4;

    /** Total number of blocks in a chunk section (16x16x16). */
    protected static final int SECTION_VOLUME = 4096;

    /** Width/height/depth of a chunk section in blocks. */
    protected static final int SECTION_SIZE = 16;

    /** Maximum reasonable palette size to prevent memory exhaustion attacks. */
    protected static final int MAX_REASONABLE_PALETTE_SIZE = 10000;

    /** Bit reader for block property data. */
    protected final BitReader propertyReader;

    /** Bit reader for section data. */
    protected final BitReader sectionReader;

    /** Reusable buffer for local palette indices in dense sections. */
    protected final int[] localPaletteBuffer;

    /** The format version of the data being decoded. */
    protected int decodedVersion;

    /** The global palette mapping indices to BlockStates. */
    protected List<S> globalPalette;

    protected final BlockStateAdapter<?, S, ?> stateAdapter;
    protected final NbtAdapter<N> nbtAdapter;
    protected final S airState;

    /**
     * Constructs a new AbstractCisDecoder with initialized readers and buffers.
     */
    protected AbstractCisDecoder(BlockStateAdapter<?, S, ?> stateAdapter, NbtAdapter<N> nbtAdapter, S airState) {
        this.stateAdapter = stateAdapter;
        this.nbtAdapter = nbtAdapter;
        this.airState = airState;
        this.propertyReader = new BitReader(new byte[0]);
        this.sectionReader = new BitReader(new byte[0]);
        this.localPaletteBuffer = new int[SECTION_VOLUME];
    }

    /**
     * Decodes the global block palette from the CIS data.
     */
    protected abstract int decodeGlobalPalette(byte[] data, int offset, Palette<S> palette) throws IOException;

    /**
     * Internal method that orchestrates the complete decoding process.
     */
    protected ChunkDelta<S, N> decodeInternal(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("CIS data too short: " + data.length + " bytes (minimum: " + HEADER_SIZE + ")");
        }

        int offset = validateHeader(data);
        ChunkDelta<S, N> delta = new ChunkDelta<>();
        delta.setSourceVersion(decodedVersion);
        Palette<S> palette = delta.getBlockPalette();

        offset = decodeGlobalPalette(data, offset, palette);
        offset = decodeSections(data, offset, delta);
        decodeBlockEntitiesAndEntities(data, offset, delta);

        delta.markSaved();
        return delta;
    }

    protected int validateHeader(byte[] data) throws IOException {
        int magic = readIntBE(data, 0);
        if (magic != CisConstants.MAGIC) {
            throw new IOException(String.format(
                    "Invalid CIS magic number: 0x%08X (expected: 0x%08X)",
                    magic, CisConstants.MAGIC));
        }

        this.decodedVersion = readIntBE(data, 4);
        if (decodedVersion < 7 || decodedVersion > CisConstants.VERSION) {
            throw new IOException(String.format(
                    "Unsupported CIS version: %d (expected 7 or %d)",
                    decodedVersion, CisConstants.VERSION));
        }

        return HEADER_SIZE;
    }

    /**
     * Decodes all chunk sections from the data stream.
     *
     * @param data   The raw byte array.
     * @param offset The current read offset.
     * @param delta  The delta to populate.
     * @return The new offset after reading sections.
     * @throws IOException If data is truncated or invalid.
     */
    private int decodeSections(byte[] data, int offset, ChunkDelta<S, N> delta) throws IOException {
        if (offset + 6 > data.length) {
            throw new IOException("Truncated data: cannot read section header");
        }

        int sectionCount = readShortBE(data, offset) & 0xFFFF;
        offset += 2;

        int sectionDataLength = readIntBE(data, offset);
        offset += 4;

        if (offset + sectionDataLength > data.length) {
            throw new IOException(String.format(
                    "Truncated data: expected %d bytes for sections, but only %d bytes remaining",
                    sectionDataLength, data.length - offset));
        }

        sectionReader.setData(data, offset, sectionDataLength);

        for (int i = 0; i < sectionCount; i++) {
            decodeSection(sectionReader, delta);
        }

        return offset + sectionDataLength;
    }

    /**
     * Decodes a single section.
     *
     * @param reader The bit reader positioned at the section start.
     * @param delta  The delta to populate.
     */
    private void decodeSection(BitReader reader, ChunkDelta<S, N> delta) {
        int sectionY = reader.readZigZag(CisConstants.SECTION_Y_BITS);
        int mode = (int) reader.read(1);

        int globalBits = calculateBitsNeeded(globalPalette.size());

        if (mode == CisConstants.SECTION_ENCODING_SPARSE) {
            decodeSparseSection(reader, delta, sectionY, globalBits);
        } else {
            decodeDenseSection(reader, delta, sectionY, globalBits);
        }
    }

    /**
     * Decodes a sparse section (list of blocks).
     */
    private void decodeSparseSection(BitReader reader, ChunkDelta<S, N> delta, int sectionY, int globalBits) {
        int blockCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);

        for (int i = 0; i < blockCount; i++) {
            int packedPos = (int) reader.read(12);
            int globalIdx = (int) reader.read(globalBits);

            int y = (packedPos >> 8) & 0xF;
            int z = (packedPos >> BITS_PER_NIBBLE) & 0xF;
            int x = packedPos & 0xF;

            S state = getStateFromPalette(globalIdx);

            if (state != null) {
                delta.addBlockChange(
                        (byte) x,
                        (sectionY << BITS_PER_NIBBLE) + y,
                        (byte) z,
                        state);
            }
        }
    }

    /**
     * Decodes a dense section (full 16x16x16 array).
     */
    private void decodeDenseSection(BitReader reader, ChunkDelta<S, N> delta, int sectionY, int globalBits) {
        int paletteBits = (decodedVersion == 7) ? 8 : CisConstants.PALETTE_SIZE_BITS;
        int localSize = (int) reader.read(paletteBits);

        // Read local palette (maps local indices to global indices)
        for (int i = 0; i < localSize; i++) {
            localPaletteBuffer[i] = (int) reader.read(globalBits);
        }

        // the localSize was encoded as-is, but the bitsPerBlock used localSize + 1 to
        // fit index 0 (null)
        int bitsPerBlock = calculateBitsNeeded(localSize + 1);

        // Read all blocks in YZX order
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int localIndex = bitsPerBlock > 0 ? (int) reader.read(bitsPerBlock) : 0;

                    // index 0 means no change (null)
                    if (localIndex == 0) {
                        continue;
                    }

                    // shift back to 0-based palette index
                    int paletteIndex = localIndex - 1;

                    if (paletteIndex >= localSize) {
                        paletteIndex = 0;
                    }

                    int globalIndex = localPaletteBuffer[paletteIndex];
                    S state = getStateFromPalette(globalIndex);

                    if (state != null) {
                        delta.addBlockChange(
                                (byte) x,
                                (sectionY << BITS_PER_NIBBLE) + y,
                                (byte) z,
                                state);
                    }
                }
            }
        }
    }

    /**
     * Decodes block entities, global entities, and optional chunk metadata from the serialized chunk
     * data stream starting at the given offset.
     *
     * @param data   raw serialized chunk bytes
     * @param offset byte offset into {@code data} where the block entity section begins
     * @param delta  target delta to populate
     */
    private void decodeBlockEntitiesAndEntities(byte[] data, int offset, ChunkDelta<S, N> delta) {
        if (offset >= data.length) {
            return;
        }

        try (DataInputStream dis = new DataInputStream(
                new ByteArrayInputStream(data, offset, data.length - offset))) {

            decodeBlockEntities(dis, delta);
            decodeEntities(dis, delta);
            if (decodedVersion >= 9) {
                decodeChunkMetadata(dis, delta, offset);
            }

        } catch (IOException e) {
            Chunkis.LOGGER.warn("Failed to decode block/entity data at offset {}: {}", offset, e.getMessage());
        }
    }

    /**
     * Reads the block entity section from the stream and registers each entry with the delta.
     * Each block entity is stored as a packed 32-bit position followed by its NBT payload.
     */
    private void decodeBlockEntities(DataInputStream dis, ChunkDelta<S, N> delta) throws IOException {
        int count = dis.readInt();

        for (int i = 0; i < count; i++) {
            int packedPos = dis.readInt();
            byte x = (byte) BlockInstruction.unpackX(packedPos);
            int  y =         BlockInstruction.unpackY(packedPos);
            byte z = (byte) BlockInstruction.unpackZ(packedPos);

            try {
                delta.addBlockEntityData(x, y, z, nbtAdapter.read(dis));
            } catch (IOException e) {
                Chunkis.LOGGER.warn("Failed to decode block entity {}/{} at {}/{}/{}", i, count, x, y, z);
                throw e;
            }
        }
    }

    /**
     * Reads the entity section from the stream, if present.
     * Entity data is optional and bounded to a sanity limit of 10 000 entries to guard against
     * corrupt or malicious payloads. An {@link EOFException} is silently ignored — older CIS
     * files may not include this section.
     */
    private void decodeEntities(DataInputStream dis, ChunkDelta<S, N> delta) throws IOException {
        final int ENTITY_COUNT_LIMIT = 10_000;

        try {
            int count = dis.readInt();
            if (count <= 0 || count > ENTITY_COUNT_LIMIT) {
                return;
            }

            List<N> entities = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entities.add(nbtAdapter.read(dis));
            }
            delta.setEntities(entities, false);

        } catch (EOFException ignored) {
            // Pre-entity CIS files end here — not an error
        }
    }

    /**
     * Reads the chunk metadata section introduced in CIS v9.
     * A leading boolean signals whether metadata is present; an {@link EOFException} indicates
     * a v9 file written before metadata was made mandatory and is treated as absent.
     *
     * @param offset original decode offset, used only for the warning message
     */
    private void decodeChunkMetadata(DataInputStream dis, ChunkDelta<S, N> delta, int offset) throws IOException {
        try {
            if (dis.readBoolean()) {
                delta.setChunkMetadata(nbtAdapter.read(dis), false);
            }
        } catch (EOFException e) {
            Chunkis.LOGGER.warn(
                    "CIS v9 chunk metadata missing at offset {} — treating as absent", offset);
        }
    }

    protected static int calculateBitsNeeded(int maxValue) {
        if (maxValue <= 1)
            return 0;
        return 32 - Integer.numberOfLeadingZeros(maxValue - 1);
    }

    protected S getStateFromPalette(int index) {
        return (index >= 0 && index < globalPalette.size())
                ? globalPalette.get(index)
                : airState;
    }

    protected static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    protected static short readShortBE(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }
}
