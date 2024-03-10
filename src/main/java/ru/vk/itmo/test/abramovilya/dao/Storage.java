package ru.vk.itmo.test.abramovilya.dao;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class Storage implements Closeable {
    static final String COMPACTED_SUFFIX = "_compacted";
    static final String COMPACTING_SUFFIX = "_compacting";
    static final String SSTABLE_BASE_NAME = "storage";
    static final String INDEX_BASE_NAME = "index";
    private static final int INDEX_ENTRY_SIZE = Integer.BYTES + Long.BYTES;
    final Path storagePath;
    final Path metaFilePath;
    final Path compactedTablesAmountFilePath;
    final Path compactionStateFilePath;
    final Compactor compactor = new Compactor(this);
    List<MemorySegment> sstableMappedList = new ArrayList<>();
    List<MemorySegment> indexMappedList = new ArrayList<>();
    final Arena arena = Arena.ofAuto();
    final ReadWriteLock sstablesAmountRWLock = new ReentrantReadWriteLock();

    Storage(Config config) throws IOException {
        storagePath = config.basePath();

        Files.createDirectories(storagePath);
        compactionStateFilePath = storagePath.resolve("compaction_state");
        compactedTablesAmountFilePath = storagePath.resolve("compacted_tables");
        metaFilePath = storagePath.resolve("meta");
        if (!Files.exists(metaFilePath)) {
            Files.createFile(metaFilePath);

            int totalSStables = 0;
            Files.writeString(metaFilePath, String.valueOf(totalSStables), StandardOpenOption.WRITE);
        }

        // Restore consistent state if db was dropped during compaction
        Path indexCompacted = storagePath.resolve(INDEX_BASE_NAME + COMPACTED_SUFFIX);
        Path storageCompacted = storagePath.resolve(SSTABLE_BASE_NAME + COMPACTED_SUFFIX);
        if (Files.exists(compactedTablesAmountFilePath)
                && (Files.exists(indexCompacted) || Files.exists(storageCompacted))) {
            if (Files.exists(compactionStateFilePath)) {
                compactor.compactionState = compactor
                        .intToCompactionState(Integer.parseInt(Files.readString(compactionStateFilePath)));
            } else {
                compactor.compactionState = CompactionState.DELETE_REMAINING;
            }
            finishCompact(Integer.parseInt(Files.readString(compactedTablesAmountFilePath)));
        } else {
            int totalSSTables = Integer.parseInt(Files.readString(metaFilePath));
            fillFileRepresentationLists(totalSSTables);
        }

        // Delete artifacts from unsuccessful compaction
        Files.deleteIfExists(storagePath.resolve(SSTABLE_BASE_NAME + COMPACTING_SUFFIX));
        Files.deleteIfExists(storagePath.resolve(INDEX_BASE_NAME + COMPACTING_SUFFIX));
    }

    Entry<MemorySegment> get(MemorySegment key) {
        int totalSStables = getTotalSStables();
        for (int sstableNum = totalSStables; sstableNum >= 0; sstableNum--) {
            var foundEntry = seekForValueInFile(key, sstableNum);
            if (foundEntry != null) {
                if (foundEntry.value() != null) {
                    return foundEntry;
                }
                return null;
            }
        }
        return null;
    }

    final int getTotalSStables() {
        sstablesAmountRWLock.readLock().lock();
        try {
            return sstableMappedList.size();
        } finally {
            sstablesAmountRWLock.readLock().unlock();
        }
    }

    private Entry<MemorySegment> seekForValueInFile(MemorySegment key, int sstableNum) {
        sstablesAmountRWLock.readLock().lock();
        try {
            if (sstableNum >= sstableMappedList.size()) {
                return null;
            }

            MemorySegment storageMapped = sstableMappedList.get(sstableNum);
            MemorySegment indexMapped = indexMappedList.get(sstableNum);

            int foundIndex = StorageUtils.upperBound(key, storageMapped, indexMapped, indexMapped.byteSize());
            long keyStorageOffset = StorageUtils.getKeyStorageOffset(indexMapped, foundIndex);
            long foundKeySize = storageMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, keyStorageOffset);
            keyStorageOffset += Long.BYTES;

            if (MemorySegment.mismatch(key,
                    0,
                    key.byteSize(),
                    storageMapped,
                    keyStorageOffset,
                    keyStorageOffset + foundKeySize) == -1) {

                return getEntryFromIndexFile(storageMapped, indexMapped, foundIndex);
            }
            return null;
        } finally {
            sstablesAmountRWLock.readLock().unlock();
        }
    }

    private Entry<MemorySegment> getEntryFromIndexFile(MemorySegment sstableMapped,
                                                       MemorySegment indexMapped,
                                                       int entryNum) {
        long offsetInStorageFile = indexMapped.get(
                ValueLayout.JAVA_LONG_UNALIGNED,
                (long) INDEX_ENTRY_SIZE * entryNum + Integer.BYTES
        );

        long keySize = sstableMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offsetInStorageFile);
        offsetInStorageFile += Long.BYTES;
        offsetInStorageFile += keySize;

        long valueSize = sstableMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offsetInStorageFile);
        offsetInStorageFile += Long.BYTES;
        MemorySegment key = sstableMapped.asSlice(offsetInStorageFile - keySize - Long.BYTES, keySize);
        MemorySegment value;
        if (valueSize == -1) {
            value = null;
        } else {
            value = sstableMapped.asSlice(offsetInStorageFile, valueSize);
        }
        return new BaseEntry<>(key, value);
    }

    void writeMapIntoFile(long sstableSize, long indexSize, NavigableMap<MemorySegment, Entry<MemorySegment>> map)
            throws IOException {
        // Блокировка нужна чтобы totalSStables было верным на момент создания файла
        sstablesAmountRWLock.readLock().lock();
        try {
            int totalSStables = getTotalSStables();
            Path sstablePath = storagePath.resolve(Storage.SSTABLE_BASE_NAME + totalSStables);
            Path indexPath = storagePath.resolve(Storage.INDEX_BASE_NAME + totalSStables);
            StorageFileWriter.writeMapIntoFile(sstableSize, indexSize, map, sstablePath, indexPath);
        } finally {
            sstablesAmountRWLock.readLock().unlock();
        }
    }

    void incTotalSStablesAmount() throws IOException {
        sstablesAmountRWLock.writeLock().lock();
        try {
            int totalSStables = sstableMappedList.size();
            Files.writeString(metaFilePath, String.valueOf(totalSStables + 1));

            Path sstablePath = storagePath.resolve(SSTABLE_BASE_NAME + totalSStables);
            Path indexPath = storagePath.resolve(INDEX_BASE_NAME + totalSStables);

            MemorySegment sstableMapped;
            try (FileChannel sstableFileChannel = FileChannel.open(sstablePath, StandardOpenOption.READ)) {
                sstableMapped =
                        sstableFileChannel
                                .map(FileChannel.MapMode.READ_ONLY, 0, Files.size(sstablePath), arena);
            }
            sstableMappedList.add(sstableMapped);

            MemorySegment indexMapped;
            try (FileChannel indexFileChannel = FileChannel.open(indexPath, StandardOpenOption.READ)) {
                indexMapped = indexFileChannel
                        .map(FileChannel.MapMode.READ_ONLY, 0, Files.size(indexPath), arena);
            }
            indexMappedList.add(indexMapped);
        } finally {
            sstablesAmountRWLock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        // Does nothing since there are no resources to close now
    }

    public MemorySegment mappedSStable(int i) {
        return sstableMappedList.get(i);
    }

    public MemorySegment mappedIndex(int i) {
        return indexMappedList.get(i);
    }

    void compact(Iterator<Entry<MemorySegment>> iterator1,
                 Iterator<Entry<MemorySegment>> iterator2,
                 int sstablesToCompact) throws IOException {
        compactor.compact(iterator1, iterator2, sstablesToCompact);
        finishCompact(sstablesToCompact);
    }

    private void finishCompact(int compactedSStablesAmount) throws IOException {
        sstablesAmountRWLock.writeLock().lock();
        try {
            compactor.finishCompact(compactedSStablesAmount);
        } finally {
            sstablesAmountRWLock.writeLock().unlock();
        }
    }

    void fillFileRepresentationLists(int newTotalSStables) throws IOException {
        for (int sstableNum = 0; sstableNum < newTotalSStables; sstableNum++) {
            Path sstablePath = storagePath.resolve(SSTABLE_BASE_NAME + sstableNum);
            Path indexPath = storagePath.resolve(INDEX_BASE_NAME + sstableNum);

            MemorySegment sstableMapped;
            try (FileChannel sstableFileChannel = FileChannel.open(sstablePath, StandardOpenOption.READ)) {
                sstableMapped = sstableFileChannel
                        .map(FileChannel.MapMode.READ_ONLY, 0, Files.size(sstablePath), this.arena);
            }
            sstableMappedList.add(sstableMapped);

            MemorySegment indexMapped;
            try (FileChannel indexFileChannel = FileChannel.open(indexPath, StandardOpenOption.READ)) {
                indexMapped = indexFileChannel
                        .map(FileChannel.MapMode.READ_ONLY, 0, Files.size(indexPath), this.arena);
            }
            indexMappedList.add(indexMapped);
        }
    }

    long findOffsetInIndex(MemorySegment from, MemorySegment to, int fileNum) {
        long readOffset = 0;
        MemorySegment storageMapped = sstableMappedList.get(fileNum);
        MemorySegment indexMapped = indexMappedList.get(fileNum);

        if (from == null && to == null) {
            return Integer.BYTES;
        } else if (from == null) {
            long firstKeySize = storageMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, readOffset);
            readOffset += Long.BYTES;
            MemorySegment firstKey = storageMapped.asSlice(readOffset, firstKeySize);
            if (DaoImpl.compareMemorySegments(firstKey, to) >= 0) {
                return -1;
            }
            return Integer.BYTES;
        } else {
            int foundIndex = StorageUtils.upperBound(from, storageMapped, indexMapped, indexMapped.byteSize());
            long keyStorageOffset = StorageUtils.getKeyStorageOffset(indexMapped, foundIndex);
            long keySize = storageMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, keyStorageOffset);
            keyStorageOffset += Long.BYTES;

            if (DaoImpl.compareMemorySegmentsUsingOffset(from, storageMapped, keyStorageOffset, keySize) > 0
                    || (to != null && DaoImpl.compareMemorySegmentsUsingOffset(
                    to, storageMapped, keyStorageOffset, keySize) <= 0)) {
                return -1;
            }
            return (long) foundIndex * INDEX_ENTRY_SIZE + Integer.BYTES;
        }
    }
}
