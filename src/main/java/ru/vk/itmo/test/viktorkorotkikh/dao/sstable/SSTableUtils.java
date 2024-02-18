package ru.vk.itmo.test.viktorkorotkikh.dao.sstable;

import ru.vk.itmo.test.viktorkorotkikh.dao.LSMPointerIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;

public final class SSTableUtils {
    static final String FILE_NAME = "sstable";

    static final String INDEX_FILE_NAME = "index.idx";

    static final String FILE_EXTENSION = ".db";

    static final String SSTABLE_INDEX_EXTENSION = ".index";

    static final String COMPACTED_PREFIX = "_compacted_";

    static final String COMPRESSION_INFO_EXTENSION = ".compressionInfo";

    static final String TMP_FILE_EXTENSION = ".tmp";

    private SSTableUtils() {
    }

    public static Path indexName(
            final boolean isCompacted,
            final Path baseDir,
            final int fileIndex
    ) {
        return isCompacted
                ? baseDir.resolve(COMPACTED_PREFIX + FILE_NAME + fileIndex + SSTABLE_INDEX_EXTENSION)
                : baseDir.resolve(FILE_NAME + fileIndex + SSTABLE_INDEX_EXTENSION);
    }

    public static Path dataName(
            final boolean isCompacted,
            final Path baseDir,
            final int fileIndex
    ) {
        return isCompacted
                ? baseDir.resolve(COMPACTED_PREFIX + FILE_NAME + fileIndex + FILE_EXTENSION)
                : baseDir.resolve(FILE_NAME + fileIndex + FILE_EXTENSION);
    }

    public static Path tempIndexName(
            final boolean isCompacted,
            final Path baseDir,
            final int fileIndex
    ) {
        if (isCompacted) {
            return baseDir.resolve(
                    COMPACTED_PREFIX + FILE_NAME + fileIndex + SSTABLE_INDEX_EXTENSION + TMP_FILE_EXTENSION
            );
        } else {
            return baseDir.resolve(FILE_NAME + fileIndex + SSTABLE_INDEX_EXTENSION + TMP_FILE_EXTENSION);
        }
    }

    public static Path tempDataName(
            final boolean isCompacted,
            final Path baseDir,
            final int fileIndex
    ) {
        return isCompacted
                ? baseDir.resolve(COMPACTED_PREFIX + FILE_NAME + fileIndex + FILE_EXTENSION + TMP_FILE_EXTENSION)
                : baseDir.resolve(FILE_NAME + fileIndex + FILE_EXTENSION + TMP_FILE_EXTENSION);
    }

    public static List<LSMPointerIterator> ssTableIterators(
            List<SSTable> ssTables,
            MemorySegment from,
            MemorySegment to
    ) {
        return ssTables.stream().map(ssTable -> {
            try {
                return ssTable.iterator(from, to);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();
    }
}
