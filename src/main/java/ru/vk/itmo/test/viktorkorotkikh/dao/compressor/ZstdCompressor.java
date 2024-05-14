package ru.vk.itmo.test.viktorkorotkikh.dao.compressor;

import com.github.luben.zstd.Zstd;

import java.io.IOException;

public class ZstdCompressor implements Compressor {
    public static final ZstdCompressor INSTANCE = new ZstdCompressor();

    @Override
    public byte[] compress(byte[] src) throws IOException {
        return Zstd.compress(src);
    }

    @Override
    public byte[] compress(byte[] src, int len) throws IOException {
        byte[] dst = new byte[(int) Zstd.compressBound(len)];
        long originalSize = Zstd.compressByteArray(
                dst,
                0,
                dst.length,
                src,
                0,
                len,
                Zstd.defaultCompressionLevel()
        );
        // zstd specific - we should write correct size of the last block
        byte[] result = new byte[(int) originalSize];
        System.arraycopy(
                dst,
                0,
                result,
                0,
                (int) originalSize
        );
        return result;
    }
}
