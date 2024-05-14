package ru.vk.itmo.test.viktorkorotkikh.dao.compressor;

import net.jpountz.lz4.LZ4Factory;

import java.io.IOException;

public class LZ4Compressor implements Compressor {
    public static final LZ4Compressor INSTANCE = new LZ4Compressor();
    private final net.jpountz.lz4.LZ4Compressor lz4FastCompressor;

    public LZ4Compressor() {
        this.lz4FastCompressor = LZ4Factory.fastestInstance().fastCompressor();
    }

    @Override
    public byte[] compress(byte[] src) throws IOException {
        return lz4FastCompressor.compress(src);
    }

    @Override
    public byte[] compress(byte[] src, int len) throws IOException {
        return lz4FastCompressor.compress(src, 0, len);
    }
}
