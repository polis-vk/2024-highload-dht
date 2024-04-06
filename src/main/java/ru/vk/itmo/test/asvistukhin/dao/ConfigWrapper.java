package ru.vk.itmo.test.asvistukhin.dao;

import java.nio.file.Path;

public class ConfigWrapper {
    private final long flushThresholdBytes;
    private final Path dataPath;
    private final Path compactTempPath;

    public ConfigWrapper(long flushThresholdBytes, Path dataPath, Path compactTempPath) {
        this.flushThresholdBytes = flushThresholdBytes;
        this.dataPath = dataPath;
        this.compactTempPath = compactTempPath;
    }

    public long getFlushThresholdBytes() {
        return flushThresholdBytes;
    }

    public Path getDataPath() {
        return dataPath;
    }

    public Path getCompactTempPath() {
        return compactTempPath;
    }
}
