package ru.vk.itmo.test.kovalevigor.dao;

import java.nio.file.Path;

public final class SSTableUtils {
    private SSTableUtils() {

    }

    public static Path getDataPath(final Path root, final String name) {
        return root.resolve(name);
    }

    public static Path getIndexPath(final Path root, final String name) {
        return root.resolve(name + "_index");
    }
}
