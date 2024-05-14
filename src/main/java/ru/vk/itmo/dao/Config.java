package ru.vk.itmo.dao;

import java.nio.file.Path;

public record Config(
        Path basePath,
        long flushThresholdBytes,
        CompressionConfig compressionConfig
) {
    public Config(
            Path basePath,
            long flushThresholdBytes
    ) {
        this(basePath, flushThresholdBytes, Config.disableCompression());
    }

    public record CompressionConfig(
            boolean enabled,
            Compressor compressor,
            int blockSize
    ) {
        public enum Compressor {
            LZ4, ZSTD;
        }
    }

    public static CompressionConfig disableCompression() {
        return new CompressionConfig(false, null, -1);
    }
}
