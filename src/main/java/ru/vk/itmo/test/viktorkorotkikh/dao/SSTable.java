package ru.vk.itmo.test.viktorkorotkikh.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.read.AbstractSSTableReader;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.read.BaseSSTableReader;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.write.AbstractSSTableWriter;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.write.BaseSSTableWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public final class SSTable {

    private static final String FILE_NAME = "sstable";

    private static final String INDEX_FILE_NAME = "index.idx";

    private static final String FILE_EXTENSION = ".db";

    private static final String SSTABLE_INDEX_EXTENSION = ".index";

    private static final String COMPACTED_PREFIX = "_compacted_";

    private static final String COMPRESSION_INFO_EXTENSION = ".compressionInfo";

    private static final String TMP_FILE_EXTENSION = ".tmp";

    private final boolean hasNoTombstones;
    private final AbstractSSTableReader reader;

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

    private SSTable(AbstractSSTableReader reader) {
        this.hasNoTombstones = reader.hasNoTombstones();
        this.reader = reader;
    }

    public static List<SSTable> load(Arena arena, Config config) throws IOException {
        Path basePath = config.basePath();
        if (checkIfCompactedExists(config)) {
            finalizeCompaction(basePath);
        }

        Path indexTmp = basePath.resolve(INDEX_FILE_NAME + TMP_FILE_EXTENSION);
        Path indexFile = basePath.resolve(INDEX_FILE_NAME);

        if (!Files.exists(indexFile)) {
            if (Files.exists(indexTmp)) {
                Files.move(indexTmp, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                if (!Files.exists(basePath)) {
                    Files.createDirectory(basePath);
                }
                Files.createFile(indexFile);
            }
        }

        List<String> existedFiles = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        List<SSTable> ssTables = new ArrayList<>(existedFiles.size());
        for (int i = 0; i < existedFiles.size(); i++) {
            ssTables.add(loadOne(arena, existedFiles.get(i).startsWith(COMPACTED_PREFIX), config, i));
        }

        return ssTables;
    }

    public static SSTable loadOne(Arena arena, boolean isCompacted, Config config, int index) throws IOException {
        try (
                FileChannel ssTableFileChannel = FileChannel.open(
                        SSTable.dataName(isCompacted, config.basePath(), index),
                        StandardOpenOption.READ
                );
                FileChannel indexFileChannel = FileChannel.open(
                        SSTable.indexName(isCompacted, config.basePath(), index),
                        StandardOpenOption.READ
                )
        ) {
            MemorySegment mappedSSTable = ssTableFileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    ssTableFileChannel.size(),
                    arena
            );
            MemorySegment mappedIndexFile = indexFileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    indexFileChannel.size(),
                    arena
            );
            AbstractSSTableReader reader = new BaseSSTableReader(
                    mappedSSTable,
                    mappedIndexFile,
                    index
            );
            return new SSTable(reader);
        }
    }

    public static boolean isCompacted(List<SSTable> ssTables) {
        return ssTables.isEmpty() || (ssTables.size() == 1 && ssTables.getFirst().hasNoTombstones);
    }

    public LSMPointerIterator iterator(MemorySegment from, MemorySegment to) throws Exception {
        return reader.iterator(from, to);
    }

    public static List<LSMPointerIterator> ssTableIterators(
            List<SSTable> ssTables,
            MemorySegment from,
            MemorySegment to
    ) {
        return ssTables.stream().map(ssTable -> {
            try {
                return ssTable.iterator(from, to);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
    }

    public static void save(MemTable memTable, int fileIndex, Config config) throws IOException {
        if (memTable.isEmpty()) return;

        final Path indexTmp = config.basePath().resolve(INDEX_FILE_NAME + TMP_FILE_EXTENSION);
        final Path indexFile = config.basePath().resolve(INDEX_FILE_NAME);

        try {
            Files.createFile(indexFile);
        } catch (FileAlreadyExistsException ignored) {
            // it is ok, actually it is normal state
        }

        AbstractSSTableWriter writer = getWriter();
        writer.write(false, memTable.values().iterator(), config.basePath(), fileIndex);

        String newFileName = dataName(false, config.basePath(), fileIndex).getFileName().toString();

        List<String> existedFiles = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        List<String> list = new ArrayList<>(existedFiles.size() + 1);
        list.addAll(existedFiles);
        list.add(newFileName);
        Files.write(
                indexTmp,
                list,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        Files.deleteIfExists(indexFile);

        Files.move(indexTmp, indexFile, StandardCopyOption.ATOMIC_MOVE);
    }

    private static AbstractSSTableWriter getWriter() {
        return new BaseSSTableWriter();
    }

    public static void compact(
            MergeIterator.MergeIteratorWithTombstoneFilter data,
            Config config
    ) throws IOException {
        AbstractSSTableWriter writer = getWriter();
        writer.write(true, data, config.basePath(), 0);
        finalizeCompaction(config.basePath());
    }

    private static Path getCompactedFilePath(Path basePath) {
        return dataName(true, basePath, 0);
    }

    private static boolean checkIfCompactedExists(Config config) {
        Path compacted = getCompactedFilePath(config.basePath());
        if (!Files.exists(compacted)) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            return !loadOne(arena, true, config, 0).hasNoTombstones;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void finalizeCompaction(Path storagePath) throws IOException {
        try (Stream<Path> stream =
                     Files.find(
                             storagePath,
                             1,
                             (path, ignored) -> {
                                 String fileName = path.getFileName().toString();
                                 return fileName.startsWith(FILE_NAME)
                                         && (
                                         fileName.endsWith(FILE_EXTENSION)
                                                 || fileName.endsWith(SSTABLE_INDEX_EXTENSION)
                                                 || fileName.endsWith(COMPRESSION_INFO_EXTENSION)
                                 );
                             })) {
            stream.forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        Path indexTmp = storagePath.resolve(INDEX_FILE_NAME + TMP_FILE_EXTENSION);
        Path indexFile = storagePath.resolve(INDEX_FILE_NAME);

        Files.deleteIfExists(indexFile);
        Files.deleteIfExists(indexTmp);

        Path compactionFile = getCompactedFilePath(storagePath);
        boolean noData = Files.size(compactionFile) == 0;
        String newFile = dataName(false, storagePath, 0).getFileName().toString();

        Files.write(
                indexTmp,
                noData ? Collections.emptyList() : Collections.singleton(newFile),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        Files.move(indexTmp, indexFile, StandardCopyOption.ATOMIC_MOVE);
        Path sstableIndexFile = indexName(true, storagePath, 0);
        if (noData) {
            Files.delete(compactionFile);
            Files.delete(sstableIndexFile);
        } else {
            Files.move(
                    compactionFile,
                    storagePath.resolve(newFile),
                    StandardCopyOption.ATOMIC_MOVE
            );
            Files.move(
                    sstableIndexFile,
                    indexName(false, storagePath, 0),
                    StandardCopyOption.ATOMIC_MOVE
            );
        }
    }

    public Entry<MemorySegment> get(MemorySegment key) {
        try {
            return reader.get(key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
