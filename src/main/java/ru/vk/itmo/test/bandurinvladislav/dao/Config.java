package ru.vk.itmo.test.bandurinvladislav.dao;

import java.nio.file.Path;

public record Config(
        Path basePath,
        long flushThresholdBytes) {
}
