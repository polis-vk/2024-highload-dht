package ru.vk.itmo.test.elenakhodosova.dao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Provides {@link SSTable} management facilities: dumping and discovery.
 *
 * @author incubos
 */
final class SSTables {
    public static final String INDEX_SUFFIX = ".index";
    public static final String DATA_SUFFIX = ".data";
    public static final long TOMBSTONE_VALUE_LENGTH = -1L;

    private static final String TEMP_SUFFIX = ".tmp";

    /**
     * Can't instantiate.
     */
    private SSTables() {
        // Only static methods
    }

    static Path indexName(
            final Path baseDir,
            final long timestamp) {
        return baseDir.resolve(timestamp + INDEX_SUFFIX);
    }

    static Path dataName(
            final Path baseDir,
            final long timestamp) {
        return baseDir.resolve(timestamp + DATA_SUFFIX);
    }

    static Path tempIndexName(
            final Path baseDir,
            final long timestamp) {
        return baseDir.resolve(timestamp + INDEX_SUFFIX + TEMP_SUFFIX);
    }

    static Path tempDataName(
            final Path baseDir,
            final long timestamp) {
        return baseDir.resolve(timestamp + DATA_SUFFIX + TEMP_SUFFIX);
    }

    /**
     * Returns {@link List} of {@link SSTable}s from <b>freshest</b> to oldest.
     */
    static List<SSTable> discover(
            final Arena arena,
            final Path baseDir) throws IOException {
        if (!Files.exists(baseDir)) {
            return Collections.emptyList();
        }

        final List<SSTable> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(baseDir)) {
            files.forEach(file -> {
                final String fileName = file.getFileName().toString();
                if (!fileName.endsWith(DATA_SUFFIX)) {
                    // Skip non data
                    return;
                }

                final long timestamp =
                        // <N>.data -> N
                        Long.parseLong(
                                fileName.substring(
                                        0,
                                        fileName.length() - DATA_SUFFIX.length()));

                try {
                    result.add(open(arena, baseDir, timestamp));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        // Sort from freshest to oldest
        result.sort((o1, o2) -> Long.compare(o2.timestamp, o1.timestamp));

        return Collections.unmodifiableList(result);
    }

    static SSTable open(
            final Arena arena,
            final Path baseDir,
            final long timestamp) throws IOException {
        final MemorySegment index =
                mapReadOnly(
                        arena,
                        indexName(baseDir, timestamp));
        final MemorySegment data =
                mapReadOnly(
                        arena,
                        dataName(baseDir, timestamp));

        return new SSTable(
                timestamp,
                index,
                data);
    }

    private static MemorySegment mapReadOnly(
            final Arena arena,
            final Path file) throws IOException {
        try (FileChannel channel =
                     FileChannel.open(
                             file,
                             StandardOpenOption.READ)) {
            return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0L,
                    Files.size(file),
                    arena);
        }
    }

    static void remove(
            final Path baseDir,
            final long timestamp) throws IOException {
        // First delete data file to make SSTable invisible
        Files.delete(dataName(baseDir, timestamp));
        Files.delete(indexName(baseDir, timestamp));
    }

    static void promote(
            final Path baseDir,
            final int from,
            final int to) throws IOException {
        // Build to progress to the same outcome
        if (Files.exists(indexName(baseDir, from))) {
            Files.move(
                    indexName(baseDir, from),
                    indexName(baseDir, to),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(dataName(baseDir, from))) {
            Files.move(
                    dataName(baseDir, from),
                    dataName(baseDir, to),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
