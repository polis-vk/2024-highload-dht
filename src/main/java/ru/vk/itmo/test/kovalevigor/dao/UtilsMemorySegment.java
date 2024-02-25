package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class UtilsMemorySegment {

    private UtilsMemorySegment() {
    }

    private static byte getByte(final MemorySegment memorySegment, final long offset) {
        return memorySegment.get(ValueLayout.JAVA_BYTE, offset);
    }

    public static long findDiff(final MemorySegment lhs, final MemorySegment rhs) {
        return lhs.mismatch(rhs);
    }

    public static int compare(final MemorySegment lhs, final MemorySegment rhs) {
        final long mismatch = findDiff(lhs, rhs);
        final long lhsSize = lhs.byteSize();
        final long rhsSize = rhs.byteSize();
        final long minSize = Math.min(lhsSize, rhsSize);
        if (mismatch == -1) {
            return 0;
        } else if (minSize == mismatch) {
            return Long.compare(lhsSize, rhsSize);
        }
        return Byte.compare(getByte(lhs, mismatch), getByte(rhs, mismatch));
    }

    public static int compareEntry(final Entry<MemorySegment> lhs, final Entry<MemorySegment> rhs) {
        return compare(lhs.key(), rhs.key());
    }

    public static MemorySegment mapSegment(
            final Path path,
            final long fileSize,
            final Arena arena,
            final FileChannel.MapMode mapMode,
            final StandardOpenOption... options
    ) throws IOException {
        try (FileChannel writerChannel = FileChannel.open(path, options)) {
            return writerChannel.map(
                    mapMode,
                    0,
                    fileSize,
                    arena
            );
        }
    }

    public static MemorySegment mapReadSegment(
            final Path path,
            final Arena arena
    ) throws IOException {
        return mapSegment(
                path,
                Files.size(path),
                arena,
                FileChannel.MapMode.READ_ONLY,
                StandardOpenOption.READ
        );
    }

    public static MemorySegment mapWriteSegment(
            final Path path,
            final long fileSize,
            final Arena arena
    ) throws IOException {
        return mapSegment(
                path,
                fileSize,
                arena,
                FileChannel.MapMode.READ_WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
    }
}
