package io.liparakis.chunkis.benchmark;

import io.liparakis.chunkis.model.BlockStateRegistry;
import io.liparakis.chunkis.codec.ZlibCompressor;
import io.liparakis.chunkis.model.ChunkDelta;
import io.liparakis.chunkis.model.CisChunk;
import io.liparakis.chunkis.model.CisSection;
import io.liparakis.chunkis.model.Palette;
import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;

/**
 * Performance benchmarks for the {@code core} module.
 * <p>
 * Each test uses {@link Benchmark#run} to measure:
 * <ul>
 * <li><b>Runtime</b> – wall-clock time per iteration (ns → ms)</li>
 * <li><b>Allocations</b> – bytes allocated on the thread per iteration</li>
 * <li><b>RAM Usage</b> – JVM heap delta (live objects after GC)</li>
 * <li><b>CPU Usage per core</b> – fraction of available cores consumed</li>
 * </ul>
 * Results are printed to {@code System.out} in a formatted table.
 * </p>
 *
 * <h3>Running specific benchmarks</h3>
 * 
 * <pre>
 *   ./gradlew :core:test --tests "*CoreBenchmarkTest*" --info
 * </pre>
 */
class CoreBenchmarkTest {

    // ─── Common constants ────────────────────────────────────────────────────

    /** Distinct block type strings used as stable state objects. */
    private static final String[] BLOCK_TYPES = buildBlockTypes();

    private static String[] buildBlockTypes() {
        String[] arr = new String[256];
        for (int i = 0; i < 256; i++)
            arr[i] = "minecraft:block_" + i;
        return arr;
    }

    private static final BlockStateRegistry<String> REGISTRY = createMockRegistry();

    private static BlockStateRegistry<String> createMockRegistry() {
        BlockStateRegistry<String> reg = new BlockStateRegistry<>("minecraft:air");
        reg.populate(java.util.Arrays.asList(BLOCK_TYPES));
        reg.freeze();
        return reg;
    }

    // ─── CisSection benchmarks ───────────────────────────────────────────────
    /**
     * Fills a single {@link CisSection} from empty → sparse → dense in one pass.
     * This exercises the sparse-to-dense conversion path.
     */
    @Test
    void bench_cisSection_fillFull() {
        BenchmarkResult r = Benchmark.run(
                "CisSection -> fill 16x16x16 (sparse -> dense)",
                5_000, 200,
                () -> {
                    CisSection<String> section = new CisSection<>(REGISTRY);
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++)
                                section.setBlock(x, y, z, BLOCK_TYPES[(x + y + z) % BLOCK_TYPES.length]);
                });
        System.out.println(r.report());
    }

    /**
     * Exercises sparse mode only by writing a small number of blocks (below the
     * sparse→dense threshold).
     */
    @Test
    void bench_cisSection_sparseOnly() {
        BenchmarkResult r = Benchmark.run(
                "CisSection -> sparse mode only (32 blocks)",
                20_000, 500,
                () -> {
                    CisSection<String> section = new CisSection<>(REGISTRY);
                    for (int i = 0; i < 32; i++) {
                        int x = i & 0xF, y = (i >> 4) & 0xF, z = (i >> 2) & 0xF;
                        section.setBlock(x, y, z, BLOCK_TYPES[i % BLOCK_TYPES.length]);
                    }
                });
        System.out.println(r.report());
    }

    /**
     * Measures the cost of random read/write access on a dense {@link CisSection}.
     */
    @Test
    void bench_cisSection_randomAccessDense() {
        // Pre-fill once, then benchmark random writes on the same dense section
        CisSection<String> denseSection = new CisSection<>(REGISTRY);
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    denseSection.setBlock(x, y, z, BLOCK_TYPES[(x + y + z) % BLOCK_TYPES.length]);

        RandomGenerator rng = RandomGenerator.getDefault();
        BenchmarkResult r = Benchmark.run(
                "CisSection -> random writes (dense, 1000 ops)",
                10_000, 300,
                () -> {
                    for (int i = 0; i < 1_000; i++) {
                        int x = rng.nextInt(16), y = rng.nextInt(16), z = rng.nextInt(16);
                        denseSection.setBlock(x, y, z, BLOCK_TYPES[rng.nextInt(BLOCK_TYPES.length)]);
                    }
                });
        System.out.println(r.report());
    }

    // ─── CisChunk benchmarks ────────────────────────────────────────────────

    /**
     * Fills a full-height chunk (384 block tall, Bedrock → Build limit) with a
     * single block type. Tests section creation and section caching.
     */
    @Test
    void bench_cisChunk_fillFullHeight() {
        BenchmarkResult r = Benchmark.run(
                "CisChunk -> fill 16x38x16 (single state)",
                500, 50,
                () -> {
                    CisChunk<String> chunk = new CisChunk<>(REGISTRY);
                    for (int y = -64; y < 320; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++)
                                chunk.addBlock(x, y, z, "minecraft:stone");
                });
        System.out.println(r.report());
    }

    /**
     * Fills a full-height chunk with many distinct block types (palette stress).
     */
    @Test
    void bench_cisChunk_fillMixedBlocks() {
        BenchmarkResult r = Benchmark.run(
                "CisChunk -> fill 16x384x16 (256 block types)",
                200, 30,
                () -> {
                    CisChunk<String> chunk = new CisChunk<>(REGISTRY);
                    for (int y = -64; y < 320; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++)
                                chunk.addBlock(x, y, z,
                                        BLOCK_TYPES[Math.abs(x + y + z) % BLOCK_TYPES.length]);
                });
        System.out.println(r.report());
    }

    /**
     * Tests {@link CisChunk#getSortedSectionIndices()} on a fully populated chunk.
     */
    @Test
    void bench_cisChunk_sortedSectionIndices() {
        CisChunk<String> chunk = new CisChunk<>(REGISTRY);
        for (int y = -64; y < 320; y++)
            chunk.addBlock(0, y, 0, "minecraft:stone");

        BenchmarkResult r = Benchmark.run(
                "CisChunk -> getSortedSectionIndices (24 sections)",
                100_000, 1_000,
                chunk::getSortedSectionIndices);
        System.out.println(r.report());
    }

    // ─── ChunkDelta benchmarks ───────────────────────────────────────────────

    /**
     * Inserts 10 000 unique block changes into a {@link ChunkDelta}.
     */
    @Test
    void bench_chunkDelta_insertLarge() {
        BenchmarkResult r = Benchmark.run(
                "ChunkDelta -> 10 000 addBlockChange (unique positions)",
                200, 20,
                () -> {
                    ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"), 10_000);
                    int count = 0;
                    outer: for (int y = 0; y < 320; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                delta.addBlockChange(x, y, z,
                                        BLOCK_TYPES[(x + y * z) % BLOCK_TYPES.length]);
                                if (++count >= 10_000)
                                    break outer;
                            }
                });
        System.out.println(r.report());
    }

    /**
     * Repeatedly updates the same set of positions to stress the position-map
     * lookup
     * and instruction update path.
     */
    @Test
    void bench_chunkDelta_updateExisting() {
        // Pre-build delta with 1000 positions
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        for (int i = 0; i < 1_000; i++) {
            int x = i & 0xF, y = (i / 16) & 0xFF, z = 0;
            delta.addBlockChange(x, y, z, BLOCK_TYPES[i % BLOCK_TYPES.length]);
        }

        BenchmarkResult r = Benchmark.run(
                "ChunkDelta -> 1000 updates to existing positions",
                5_000, 200,
                () -> {
                    for (int i = 0; i < 1_000; i++) {
                        int x = i & 0xF, y = (i / 16) & 0xFF, z = 0;
                        delta.addBlockChange(x, y, z, BLOCK_TYPES[(i + 1) % BLOCK_TYPES.length]);
                    }
                });
        System.out.println(r.report());
    }

    /**
     * Exercises the full ChunkDelta visitor pattern with blocks + block entities.
     */
    @Test
    void bench_chunkDelta_visitor() {
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        for (int i = 0; i < 5_000; i++) {
            int x = i & 0xF, z = (i >> 4) & 0xF, y = i >> 8;
            delta.addBlockChange(x, y, z, BLOCK_TYPES[i % BLOCK_TYPES.length]);
            if (i % 50 == 0) {
                delta.addBlockEntityData(x, y, z, "{tile:chest,items:[]}");
            }
        }

        int[] visitedBlocks = { 0 };
        int[] visitedBE = { 0 };
        BenchmarkResult r = Benchmark.run(
                "ChunkDelta –> visitor (5000 blocks, 100 block entities)",
                2_000, 100,
                () -> {
                    visitedBlocks[0] = 0;
                    visitedBE[0] = 0;
                    delta.accept(new ChunkDelta.DeltaVisitor<>() {
                        @Override
                        public void visitBlock(int x, int y, int z, String s) {
                            visitedBlocks[0]++;
                        }

                        @Override
                        public void visitBlockEntity(int x, int y, int z, String nbt) {
                            visitedBE[0]++;
                        }

                        @Override
                        public void visitEntity(String nbt) {
                        }
                    });
                });
        System.out.println(r.report());
        if (visitedBlocks[0] != 5000 || visitedBE[0] != 100) {
            throw new IllegalStateException("Visitor failed to visit all elements");
        }
    }

    // ─── Palette benchmarks ─────────────────────────────────────────────────

    /**
     * Inserts 256 unique entries and then performs 256 × 1000 lookups.
     */
    @Test
    void bench_palette_lookupHeavy() {
        Palette<String> palette = new Palette<>();
        for (String block : BLOCK_TYPES)
            palette.getOrAdd(block);

        BenchmarkResult r = Benchmark.run(
                "Palette -> 256k getOrAdd lookups (existing)",
                10_000, 500,
                () -> {
                    for (String block : BLOCK_TYPES) {
                        palette.getOrAdd(block);
                    }
                });
        System.out.println(r.report());
    }

    /**
     * Inserts 256 entries into a fresh palette (insertion-heavy path).
     */
    @Test
    void bench_palette_insertFresh() {
        BenchmarkResult r = Benchmark.run(
                "Palette -> insert 256 unique entries fresh",
                10_000, 500,
                () -> {
                    Palette<String> palette = new Palette<>();
                    for (String block : BLOCK_TYPES)
                        palette.getOrAdd(block);
                });
        System.out.println(r.report());
    }

    // ─── ZlibCompressor benchmarks ──────────────────────────────────────────

    /**
     * Compresses a realistic 8KB block of "chunk-like" repetitive data.
     */
    @Test
    void bench_zlibCompressor_compress8k() {
        byte[] payload = buildRepetitivePayload(8 * 1024);
        ZlibCompressor compressor = new ZlibCompressor();

        BenchmarkResult r = Benchmark.run(
                "ZlibCompressor -> compress 8 KB repetitive",
                2_000, 200,
                () -> compressor.compress(payload));
        System.out.println(r.report());
    }

    /**
     * Full round‑trip: compress then immediately decompress an 8KB payload.
     */
    @Test
    void bench_zlibCompressor_roundTrip8k() {
        byte[] payload = buildRepetitivePayload(8 * 1024);
        ZlibCompressor compressor = new ZlibCompressor();
        byte[] compressed = compressor.compress(payload);

        BenchmarkResult r = Benchmark.run(
                "ZlibCompressor -> decompress 8 KB",
                2_000, 200,
                () -> {
                    try {
                        compressor.decompress(compressed);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        System.out.println(r.report());
    }

    /**
     * Compress a larger 64KB payload representing a whole-chunk serialization.
     */
    @Test
    void bench_zlibCompressor_compress64k() {
        byte[] payload = buildRepetitivePayload(64 * 1024);
        ZlibCompressor compressor = new ZlibCompressor();

        BenchmarkResult r = Benchmark.run(
                "ZlibCompressor -> compress 64 KB",
                500, 50,
                () -> compressor.compress(payload));
        System.out.println(r.report());
    }

    // ─── Combined / end-to-end benchmarks ───────────────────────────────────

    /**
     * Simulates a full "chunk populate and delta" lifecycle: fill a CisChunk,
     * iterate sections, and record every block into a ChunkDelta.
     */
    @Test
    void bench_endToEnd_chunkPopulateAndDelta() {
        BenchmarkResult r = Benchmark.run(
                "End-to-end: CisChunk fill + ChunkDelta (1 section, 4096 blocks)",
                100, 10,
                () -> {
                    CisChunk<String> chunk = new CisChunk<>(REGISTRY);
                    ChunkDelta<String, String> delta = new ChunkDelta<>(s -> false);

                    // Fill one section (y = 0..15)
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                String state = BLOCK_TYPES[(x + y + z) % BLOCK_TYPES.length];
                                chunk.addBlock(x, y, z, state);
                                delta.addBlockChange(x, y, z, state, false);
                            }
                });
        System.out.println(r.report());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static byte[] buildRepetitivePayload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            // Minecraft NBT-like pattern: mostly repeated bytes with occasional variety
            data[i] = (byte) (i % 32 == 0 ? (i & 0xFF) : 0x08);
        }
        return data;
    }
}
