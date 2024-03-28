package ru.vk.itmo.test.georgiidalbeev.dao;

import java.nio.file.Path;

public record Config(
        Path basePath,
        long flushThresholdBytes) {
}
