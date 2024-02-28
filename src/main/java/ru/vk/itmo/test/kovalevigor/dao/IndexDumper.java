package ru.vk.itmo.test.kovalevigor.dao;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

public class IndexDumper extends Dumper {

    public static final long HEAD_SIZE = 2 * ValueLayout.JAVA_LONG.byteSize();
    public static final long ENTRY_SIZE = 2 * ValueLayout.JAVA_LONG.byteSize();
    public long keysSize;
    public long valuesSize;
    private long nullCount;

    protected IndexDumper(
            final long entryCount,
            final Path path,
            final Arena arena
    ) throws IOException {
        super(path, getSize(entryCount), arena);
        this.keysSize = 0;
        this.valuesSize = 0;
        this.nullCount = 0;
        offset = HEAD_SIZE;
    }

    public static long getSize(final long entryCount) {
        return HEAD_SIZE + entryCount * ENTRY_SIZE;
    }

    public void setKeysSize(final long keysSize) {
        this.keysSize = keysSize;
    }

    public void setValuesSize(final long valuesSize) {
        this.valuesSize = valuesSize;
    }

    @Override
    protected void writeHead() {
        writeLong(memorySegment, 0, keysSize);
        writeLong(memorySegment, ValueLayout.JAVA_LONG.byteSize(), valuesSize);
    }

    private void fillNulls(final long valueOffset) {
        long curOffset = offset - ValueLayout.JAVA_LONG.byteSize();
        while (nullCount > 0) {
            writeLong(memorySegment, curOffset, -valueOffset - 1);
            curOffset -= ENTRY_SIZE;
            nullCount -= 1;
        }
    }

    public void writeEntry(final long keyOffset, final long valueOffset) {
        if (valueOffset < -1) {
            throw new IllegalArgumentException("Invalid valueOffset value. Should be equal or greater than -1");
        }
        if (valueOffset == -1) {
            nullCount += 1;
        } else {
            fillNulls(valueOffset);
        }
        writeLong(keyOffset);
        writeLong(valueOffset);
    }

    @Override
    public void close() {
        fillNulls(valuesSize);
    }
}
