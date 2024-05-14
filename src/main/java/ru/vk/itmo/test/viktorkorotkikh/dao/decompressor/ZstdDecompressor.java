package ru.vk.itmo.test.viktorkorotkikh.dao.decompressor;

import com.github.luben.zstd.Zstd;

import java.io.IOException;

public class ZstdDecompressor implements Decompressor {
    public static final ZstdDecompressor INSTANCE = new ZstdDecompressor();

    @Override
    public void decompress(
            byte[] src,
            byte[] dest,
            int destOff,
            int uncompressedSize,
            int compressedSize
    ) throws IOException {
        Zstd.decompressByteArray(dest, destOff, uncompressedSize, src, 0, compressedSize);
    }
}
