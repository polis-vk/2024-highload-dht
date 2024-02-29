package ru.vk.itmo.test.kovalevigor.dao;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;

public abstract class Dumper extends SegmentWriter implements AutoCloseable {

    protected Dumper(final Path path, final long fileSize, final Arena arena) throws IOException {
        super(path, fileSize, arena);
    }

    protected abstract void writeHead();

    @Override
    public abstract void close() throws IOException;
}
