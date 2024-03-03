package ru.vk.itmo.test.kovalchukvladislav.dao.storage;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.DaoIterator;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.EntryExtractor;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.SimpleDaoLoggerUtility;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.StorageIterator;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.TableInfo;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class SSTableStorageImpl<D, E extends Entry<D>> implements SSTableStorage<D, E> {
    private static final StandardCopyOption[] MOVE_OPTIONS = new StandardCopyOption[]{
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
    };
    private static final Logger logger = SimpleDaoLoggerUtility.createLogger(SSTableStorageImpl.class);
    private final Path basePath;
    private final String metadataFilename;
    private final String dataPrefix;
    private final String offsetsPrefix;
    private final Arena arena = Arena.ofShared();
    private final EntryExtractor<D, E> extractor;
    private final Set<Path> filesToDelete = ConcurrentHashMap.newKeySet();
    private volatile State state;

    public SSTableStorageImpl(Path basePath,
                              String metadataFilename,
                              String dbFilenamePrefix,
                              String offsetsPrefix,
                              EntryExtractor<D, E> extractor) throws IOException {
        this.basePath = basePath;
        this.metadataFilename = metadataFilename;
        this.dataPrefix = dbFilenamePrefix;
        this.offsetsPrefix = offsetsPrefix;
        this.extractor = extractor;
        if (!Files.exists(basePath)) {
            Files.createDirectory(basePath);
        }
        this.state = reloadSSTableIds();
    }

    @SuppressWarnings("unused")
    // Компилятор ругается на unused переменные внутри record, хотя они очень даже used
    private record State(List<String> ssTableIds, List<MemorySegment> data, List<MemorySegment> offsets) {
        public int getCount() {
            return ssTableIds.size();
        }
    }

    @Override
    public E get(D key) {
        State currentState = state;
        for (int i = currentState.getCount() - 1; i >= 0; i--) {
            MemorySegment storage = currentState.data.get(i);
            MemorySegment offsets = currentState.offsets.get(i);

            long offset = extractor.findLowerBoundValueOffset(key, storage, offsets);
            if (offset == -1) {
                continue;
            }
            D lowerBoundKey = extractor.readValue(storage, offset);

            if (extractor.compare(lowerBoundKey, key) == 0) {
                long valueOffset = offset + extractor.size(lowerBoundKey);
                D value = extractor.readValue(storage, valueOffset);
                return extractor.createEntry(lowerBoundKey, value);
            }
        }
        return null;
    }

    @Override
    public List<Iterator<E>> getIterators(D from, D to) {
        State currentState = state;
        List<Iterator<E>> iterators = new ArrayList<>(currentState.getCount());
        for (int i = currentState.getCount() - 1; i >= 0; i--) {
            MemorySegment storage = currentState.data.get(i);
            MemorySegment offsets = currentState.offsets.get(i);
            iterators.add(new StorageIterator<>(from, to, storage, offsets, extractor));
        }
        return iterators;
    }

    // State может поменяться только в addSSTableId (используется при flush() для обновления состояния) и compact().
    // Оба метода никогда не вызываются одновременно из AbstractBasedOnSSTableDao, синхронизация не нужна.
    @Override
    @SuppressWarnings("unused")
    // Компилятор ругается на unused ignoredPath, хотя в названии переменной есть unused
    public void addSSTableId(String id, boolean needRefresh) throws IOException {
        Path ignoredPath = addSSTableId(basePath, id);
        if (needRefresh) {
            state = reloadSSTableIds();
        }
    }

    @Override
    public void compact() throws IOException {
        List<String> ssTableIds = state.ssTableIds;
        if (ssTableIds.size() <= 1) {
            logger.info("SSTables <= 1, not compacting: " + ssTableIds);
            return;
        }

        compactAndChangeMetadata();
        state = reloadSSTableIds();
        filesToDelete.addAll(convertSSTableIdsToPath(ssTableIds));
    }

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
            StorageUtility.deleteUnusedFiles(logger, filesToDelete.toArray(Path[]::new));
        }
    }

    private State reloadSSTableIds() throws IOException {
        List<String> ssTableIds = readSSTableIds();
        logger.info(() -> String.format("Reloading files from %s", basePath));
        List<MemorySegment> newDbMappedSegments = new ArrayList<>(ssTableIds.size());
        List<MemorySegment> newOffsetMappedSegments = new ArrayList<>(ssTableIds.size());

        for (String ssTableId : ssTableIds) {
            readFileAndMapToSegment(newDbMappedSegments, newOffsetMappedSegments, ssTableId);
        }
        logger.info(() -> String.format("Reloaded %d files", ssTableIds.size()));

        return new State(ssTableIds, newDbMappedSegments, newOffsetMappedSegments);
    }

    private List<String> readSSTableIds() throws IOException {
        Path metadataPath = basePath.resolve(metadataFilename);
        if (!Files.exists(metadataPath)) {
            return Collections.emptyList();
        }
        return Files.readAllLines(metadataPath, StandardCharsets.UTF_8);
    }

    private void readFileAndMapToSegment(List<MemorySegment> dbMappedResult,
                                         List<MemorySegment> offsetMappedResult,
                                         String timestamp) throws IOException {
        Path dbPath = basePath.resolve(dataPrefix + timestamp);
        Path offsetsPath = basePath.resolve(offsetsPrefix + timestamp);
        if (!Files.exists(dbPath) || !Files.exists(offsetsPath)) {
            throw new FileNotFoundException("File under path " + dbPath + " or " + offsetsPath + " doesn't exists");
        }

        logger.info(() -> String.format("Reading files with timestamp %s", timestamp));

        try (FileChannel dbChannel = FileChannel.open(dbPath, StandardOpenOption.READ);
             FileChannel offsetChannel = FileChannel.open(offsetsPath, StandardOpenOption.READ)) {

            MemorySegment db = dbChannel.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(dbPath), arena);
            MemorySegment offsets =
                    offsetChannel.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(offsetsPath), arena);
            dbMappedResult.add(db);
            offsetMappedResult.add(offsets);
        }
        logger.info(() -> String.format("Successfully read files with %s timestamp", timestamp));
    }

    private Path addSSTableId(Path path, String id) throws IOException {
        return Files.writeString(path.resolve(metadataFilename), id + System.lineSeparator(),
                StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
    }

    private void compactAndChangeMetadata() throws IOException {
        Path tempDirectory = Files.createTempDirectory(null);
        String timestamp = String.valueOf(System.currentTimeMillis());

        Path newSSTable = null;
        Path newOffsetsTable = null;
        Path tmpSSTable = tempDirectory.resolve(dataPrefix + timestamp);
        Path tmpOffsetsTable = tempDirectory.resolve(offsetsPrefix + timestamp);

        try {
            Iterator<E> iterator = getIterator();
            TableInfo info = calculateStorageTableInfo();
            logger.info(() -> String.format("Compacting started to dir %s, timestamp %s, info %s",
                    tempDirectory, timestamp, info));

            StorageUtility.writeData(tmpSSTable, tmpOffsetsTable, iterator, info, extractor);
            Path tmpMetadata = addSSTableId(tempDirectory, timestamp);
            Path newMetadata = basePath.resolve(metadataFilename);

            newSSTable = Files.move(tmpSSTable, basePath.resolve(dataPrefix + timestamp), MOVE_OPTIONS);
            newOffsetsTable = Files.move(tmpOffsetsTable, basePath.resolve(offsetsPrefix + timestamp),
                    MOVE_OPTIONS);
            Files.move(tmpMetadata, newMetadata, MOVE_OPTIONS);
        } catch (Exception e) {
            if (newOffsetsTable != null) {
                StorageUtility.deleteUnusedFiles(logger, newSSTable, newOffsetsTable);
            } else if (newSSTable != null) {
                StorageUtility.deleteUnusedFiles(logger, newSSTable);
            }
            throw e;
        } finally {
            StorageUtility.deleteUnusedFiles(logger, tempDirectory);
        }
        logger.info(() -> String.format("Compacted to dir %s, timestamp %s", basePath, timestamp));
    }

    private TableInfo calculateStorageTableInfo() {
        Iterator<E> iterator = getIterator();
        long size = 0;
        int count = 0;
        while (iterator.hasNext()) {
            count++;
            size += extractor.size(iterator.next());
        }
        return new TableInfo(count, size);
    }

    private Iterator<E> getIterator() {
        return new DaoIterator<>(getIterators(null, null), extractor);
    }

    private List<Path> convertSSTableIdsToPath(List<String> ssTableIds) {
        List<Path> result = new ArrayList<>(ssTableIds.size() * 2);
        for (String ssTableId : ssTableIds) {
            result.add(basePath.resolve(dataPrefix + ssTableId));
            result.add(basePath.resolve(offsetsPrefix + ssTableId));
        }
        return result;
    }
}
