package ru.vk.itmo.test.kislovdanil.dao;

import java.nio.file.Path;

public record Config(
        Path basePath,
        long flushThresholdBytes) {
}
