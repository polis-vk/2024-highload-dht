package ru.vk.itmo.test.kovalevigor.dao;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

public class SegmentWriter {

    public final MemorySegment memorySegment;
    public final Path path;
    public long offset;

    public SegmentWriter(final Path path, final long size, final Arena arena) throws IOException {
        this.memorySegment = UtilsMemorySegment.mapWriteSegment(path, size, arena);
        this.path = path;
        this.offset = 0;
    }

    protected static long writeLong(final MemorySegment memorySegment, final long offset, final long value) {
        memorySegment.set(ValueLayout.JAVA_LONG, offset, value);
        return offset + ValueLayout.JAVA_LONG.byteSize();
    }

    protected void writeLong(final long value) {
        offset = writeLong(memorySegment, offset, value);
    }

    protected long writeMemorySegment(final MemorySegment segment, final long offset, final long size) {
        MemorySegment.copy(
                segment,
                0,
                memorySegment,
                offset,
                size
        );
        return offset + size;
    }

    protected void writeMemorySegment(final MemorySegment segment) {
        offset = writeMemorySegment(segment, offset, segment.byteSize());
    }
}
