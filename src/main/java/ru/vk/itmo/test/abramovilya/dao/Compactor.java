package ru.vk.itmo.test.abramovilya.dao;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;

class Compactor {
    private final Storage storage;
    CompactionState compactionState;

    Compactor(Storage storage) {
        this.storage = storage;
        this.compactionState = CompactionState.NO_COMPACTION;
    }

    CompactionState intToCompactionState(int i) {
        for (CompactionState cs : CompactionState.values()) {
            if (cs.ordinal() == i) {
                return cs;
            }
        }
        throw new IllegalArgumentException("Unknown state: " + i);
    }

    // compact вызывается из dao однопоточно
    void compact(Iterator<Entry<MemorySegment>> iterator1,
                 Iterator<Entry<MemorySegment>> iterator2,
                 int sstablesToCompact) throws IOException {
        if (!Files.exists(storage.compactedTablesAmountFilePath)) {
            Files.createFile(storage.compactedTablesAmountFilePath);
        }
        Files.writeString(storage.compactedTablesAmountFilePath,
                String.valueOf(sstablesToCompact),
                StandardOpenOption.WRITE);

        Entry<Long> storageIndexSize = calcCompactedSStableIndexSize(iterator1);
        Path compactingSStablePath =
                storage.storagePath.resolve(Storage.SSTABLE_BASE_NAME + Storage.COMPACTING_SUFFIX);
        Path compactingIndexPath =
                storage.storagePath.resolve(Storage.INDEX_BASE_NAME + Storage.COMPACTING_SUFFIX);
        StorageFileWriter.writeIteratorIntoFile(storageIndexSize.key(),
                storageIndexSize.value(),
                iterator2,
                compactingSStablePath,
                compactingIndexPath);

        Path compactedSStablePath =
                storage.storagePath.resolve(Storage.SSTABLE_BASE_NAME + Storage.COMPACTED_SUFFIX);
        Path compactedIndexPath =
                storage.storagePath.resolve(Storage.INDEX_BASE_NAME + Storage.COMPACTED_SUFFIX);
        Files.move(compactingSStablePath, compactedSStablePath, StandardCopyOption.ATOMIC_MOVE);
        Files.move(compactingIndexPath, compactedIndexPath, StandardCopyOption.ATOMIC_MOVE);

        compactionState = CompactionState.DELETE_REMAINING;
        if (!Files.exists(storage.compactionStateFilePath)) {
            Files.createFile(storage.compactionStateFilePath);
        }
        Files.writeString(storage.compactionStateFilePath,
                String.valueOf(compactionState.ordinal()),
                StandardOpenOption.WRITE);
    }

    void finishCompact(int compactedSStablesAmount) throws IOException {
        if (compactionState == CompactionState.DELETE_REMAINING) {
            for (int i = 0; i < compactedSStablesAmount; i++) {
                Files.deleteIfExists(storage.storagePath.resolve(Storage.SSTABLE_BASE_NAME + i));
                Files.deleteIfExists(storage.storagePath.resolve(Storage.INDEX_BASE_NAME + i));
            }
            compactionState = CompactionState.MOVE_COMPACTED;
            Files.writeString(storage.compactionStateFilePath,
                    String.valueOf(compactionState.ordinal()),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }

        if (compactionState == CompactionState.MOVE_COMPACTED) {
            Path compactedSStablePath =
                    storage.storagePath.resolve(Storage.SSTABLE_BASE_NAME + Storage.COMPACTED_SUFFIX);
            if (Files.exists(compactedSStablePath)) {
                Files.move(compactedSStablePath,
                        storage.storagePath.resolve(Storage.SSTABLE_BASE_NAME + 0),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Path compactedIndexPath =
                    storage.storagePath.resolve(Storage.INDEX_BASE_NAME + Storage.COMPACTED_SUFFIX);

            if (Files.exists(compactedIndexPath)) {
                Files.move(compactedIndexPath,
                        storage.storagePath.resolve(Storage.INDEX_BASE_NAME + 0),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }

        int totalSStables = Integer.parseInt(Files.readString(storage.metaFilePath));

        storage.sstableMappedList = new ArrayList<>();
        storage.indexMappedList = new ArrayList<>();

        for (int i = compactedSStablesAmount; i < totalSStables; i++) {
            Path oldSStablePath = storage.storagePath.resolve(Storage.SSTABLE_BASE_NAME + i);
            Path newSStablePath = storage.storagePath.resolve(
                    Storage.SSTABLE_BASE_NAME + convertOldFileNumToNew(i, compactedSStablesAmount));
            Path oldIndexPath = storage.storagePath.resolve(Storage.INDEX_BASE_NAME + i);
            Path newIndexPath = storage.storagePath.resolve(
                    Storage.INDEX_BASE_NAME + convertOldFileNumToNew(i, compactedSStablesAmount));

            if (Files.exists(oldSStablePath)) {
                Files.move(oldSStablePath, newSStablePath, StandardCopyOption.ATOMIC_MOVE);
            }
            if (Files.exists(oldIndexPath)) {
                Files.move(oldIndexPath, newIndexPath, StandardCopyOption.ATOMIC_MOVE);
            }
        }

        int newTotalSStables = convertOldFileNumToNew(totalSStables, compactedSStablesAmount);
        storage.fillFileRepresentationLists(newTotalSStables);

        Files.writeString(storage.metaFilePath, String.valueOf(newTotalSStables));
        Files.deleteIfExists(storage.compactedTablesAmountFilePath);
    }

    private static int convertOldFileNumToNew(int oldNum, int compactedSStablesNum) {
        return oldNum - compactedSStablesNum + 1;
    }

    private Entry<Long> calcCompactedSStableIndexSize(Iterator<Entry<MemorySegment>> iterator) {
        long storageSize = 0;
        long indexSize = 0;
        while (iterator.hasNext()) {
            Entry<MemorySegment> entry = iterator.next();
            storageSize += entry.key().byteSize() + entry.value().byteSize() + 2 * Long.BYTES;
            indexSize += Integer.BYTES + Long.BYTES;
        }
        return new BaseEntry<>(storageSize, indexSize);
    }
}
