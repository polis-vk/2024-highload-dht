package ru.vk.itmo.test.nikitaprokopev.dao;

public record Config(
        java.nio.file.Path basePath,
        long flushThresholdBytes) {
}
