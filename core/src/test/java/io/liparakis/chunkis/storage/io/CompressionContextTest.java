package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.storage.model.CisConstants;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;

class CompressionContextTest {

    @Test
    void usesUncompressedDeflateForSmallPayloads() {
        assertThat(CompressionContext.compressionLevelFor(CisConstants.UNCOMPRESSED_DELTA_THRESHOLD))
                .isEqualTo(Deflater.NO_COMPRESSION);
        assertThat(CompressionContext.compressionLevelFor(CisConstants.UNCOMPRESSED_DELTA_THRESHOLD + 1))
                .isEqualTo(CisConstants.COMPRESSION_LEVEL);
    }

    @Test
    void roundTripsPayloadsAcrossAdaptiveCompressionLevels() throws Exception {
        final CompressionContext context = new CompressionContext();
        final byte[] smallPayload = new byte[CisConstants.UNCOMPRESSED_DELTA_THRESHOLD];
        final byte[] largePayload = new byte[CisConstants.UNCOMPRESSED_DELTA_THRESHOLD + 1024];

        for (int i = 0; i < smallPayload.length; i++) {
            smallPayload[i] = (byte) i;
        }
        Arrays.fill(largePayload, (byte) 42);

        assertThat(context.decompress(context.compress(smallPayload))).isEqualTo(smallPayload);
        assertThat(context.decompress(context.compress(largePayload))).isEqualTo(largePayload);
        assertThat(context.decompress(context.compress(smallPayload))).isEqualTo(smallPayload);
    }
}
