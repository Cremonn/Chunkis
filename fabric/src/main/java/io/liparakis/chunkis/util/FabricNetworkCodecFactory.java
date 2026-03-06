package io.liparakis.chunkis.util;

import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.codec.DefaultBlockStatePacker;
import io.liparakis.chunkis.codec.interfaces.BlockStatePacker;
import io.liparakis.chunkis.codec.stream.CisNetworkDecoder;
import io.liparakis.chunkis.codec.stream.CisNetworkEncoder;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Property;

/**
 * Factory for creating Fabric-specific network encoders and decoders.
 *
 * <p>
 * All shared adapter instances ({@link FabricBlockRegistryAdapter},
 * {@link FabricBlockStateAdapter}, {@link FabricNbtAdapter}) are immutable and
 * thread-safe, so they are constructed once and reused across all codec instances.
 *
 * <p>
 * <b>Creation strategy:</b> Encoder and decoder singletons are lazily initialized
 * via the initialization-on-demand holder idiom. The JVM guarantees that the holder
 * class is loaded and its static fields initialized exactly once, on the first call
 * to {@link #createDecoder()} or {@link #createEncoder()} respectively. This is
 * allocation-free on the hot path and requires no {@code volatile} fields or
 * {@code synchronized} blocks.
 *
 * <p>
 * <b>Thread safety:</b> All public methods are thread-safe.
 *
 * @author Liparakis
 * @version 1.2
 */
public final class FabricNetworkCodecFactory {
    private static final BlockRegistryAdapter<Block> REGISTRY_ADAPTER = new FabricBlockRegistryAdapter();
    private static final BlockStateAdapter<Block, BlockState, Property<?>> STATE_ADAPTER = new FabricBlockStateAdapter();
    private static final NbtAdapter<NbtCompound> NBT_ADAPTER = new FabricNbtAdapter();
    private static final BlockStatePacker<Block, BlockState> PROPERTY_PACKER = new DefaultBlockStatePacker<>(STATE_ADAPTER);

    /**
     * Cached air state used as the "no block" sentinel in codec operations.
     */
    private static final BlockState AIR_STATE = Blocks.AIR.getDefaultState();

    private FabricNetworkCodecFactory() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns the shared decoder singleton, creating it on the first call.
     *
     * <p>
     * Thread safety is guaranteed by the JVM class-loading contract: the
     * {@link DecoderHolder} class is initialized exactly once, the first time
     * this method is invoked. No {@code volatile} read or {@code synchronized}
     * block is needed on subsequent calls.
     *
     * @return the shared {@link CisNetworkDecoder} instance
     */
    public static CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> createDecoder() {
        return DecoderHolder.INSTANCE;
    }

    /**
     * Returns the shared encoder singleton, creating it on the first call.
     *
     * <p>
     * Thread safety is guaranteed by the JVM class-loading contract: the
     * {@link EncoderHolder} class is initialized exactly once, the first time
     * this method is invoked. No {@code volatile} read or {@code synchronized}
     * block is needed on subsequent calls.
     *
     * @return the shared {@link CisNetworkEncoder} instance
     */
    public static CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> createEncoder() {
        return EncoderHolder.INSTANCE;
    }

    /**
     * Holder for the {@link CisNetworkDecoder} singleton.
     *
     * <p>
     * The JVM loads and initializes this class at most once, on the first
     * access to {@link #INSTANCE}, providing lazy initialization without
     * any explicit synchronization overhead.
     */
    private static final class DecoderHolder {
        static final CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> INSTANCE =
                new CisNetworkDecoder<>(REGISTRY_ADAPTER, PROPERTY_PACKER, STATE_ADAPTER, NBT_ADAPTER, AIR_STATE);
    }

    /**
     * Holder for the {@link CisNetworkEncoder} singleton.
     *
     * <p>
     * The JVM loads and initializes this class at most once, on the first
     * access to {@link #INSTANCE}, providing lazy initialization without
     * any explicit synchronization overhead.
     */
    private static final class EncoderHolder {
        static final CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> INSTANCE =
                new CisNetworkEncoder<>(REGISTRY_ADAPTER, PROPERTY_PACKER, STATE_ADAPTER, NBT_ADAPTER, AIR_STATE);
    }
}