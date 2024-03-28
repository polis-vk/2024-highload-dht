package ru.vk.itmo.test.tyapuevdmitrij.dao;

import ru.vk.itmo.dao.Entry;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Iterator;

public class StorageHelper {
    static final String SS_TABLE_FILE_NAME = "ssTable";
    static final String TEMP_SS_TABLE_FILE_NAME = "tempSsTable";
    static final String COMPACTED_FILE_NAME = "compact";
    private long ssTablesEntryQuantity;
    protected long memTableEntriesCount;

    public int findSsTablesQuantity(Path ssTablePath) {
        File[] files = getDirectoryFiles(ssTablePath);
        if (files.length == 0) {
            return 0;
        }
        long countSsTables = 0L;
        for (File file : files) {
            if (file.isFile() && file.getName().contains(SS_TABLE_FILE_NAME)) {
                countSsTables++;
            }
        }
        return (int) countSsTables;
    }

    public void deleteOldSsTables(Path ssTablePath) {
        File[] files = getDirectoryFiles(ssTablePath);
        for (File file : files) {
            if (file.getName().contains(SS_TABLE_FILE_NAME)) {
                try {
                    Files.delete(file.toPath());
                } catch (IOException e) {
                    throw new DAOException("Can't delete file " + file.toPath(), e);
                }
            }
        }
    }

    private File[] getDirectoryFiles(Path ssTablePath) {
        File directory = new File(ssTablePath.toUri());
        if (!directory.exists() || !directory.isDirectory()) {
            return new File[0];
        }
        return directory.listFiles();
    }

    public void renameCompactedSsTable(Path ssTablePath) {
        Path compactionFile = ssTablePath.resolve(COMPACTED_FILE_NAME);
        Path newCompactionFile = ssTablePath.resolve(SS_TABLE_FILE_NAME + 0);
        try {
            Files.move(
                    compactionFile,
                    newCompactionFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException e) {
            throw new DAOException("Can't rename file", e);
        }
    }

    public long getSsTableDataByteSize(Iterable<Entry<MemorySegment>> memTableEntries) {
        long ssTableDataByteSize = 0;
        long entriesCount = 0;
        for (Entry<MemorySegment> entry : memTableEntries) {
            ssTableDataByteSize += entry.key().byteSize();
            if (entry.value() != null) {
                ssTableDataByteSize += entry.value().byteSize();
            }
            entriesCount++;
        }
        memTableEntriesCount = entriesCount;
        return ssTableDataByteSize + entriesCount * Long.BYTES * 4L + Long.BYTES;
    }

    public void saveDataForCompaction(State stateNow, Path ssTablePath) {
        Iterator<Entry<MemorySegment>> ssTablesIterator = stateNow.storage.range(
                Collections.emptyIterator(), Collections.emptyIterator(),
                null, null, MemorySegmentComparator.getMemorySegmentComparator());
        Path compactionPath = ssTablePath.resolve(StorageHelper.COMPACTED_FILE_NAME);
        try (Arena writeArena = Arena.ofConfined()) {
            MemorySegment buffer = NmapBuffer.getWriteBufferToSsTable(getCompactionTableByteSize(stateNow),
                    compactionPath, writeArena);
            long bufferByteSize = buffer.byteSize();
            buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, bufferByteSize - Long.BYTES, ssTablesEntryQuantity);
            long dataOffset = 0;
            long indexOffset = bufferByteSize - Long.BYTES - ssTablesEntryQuantity * 2L * Long.BYTES;
            while (ssTablesIterator.hasNext()) {
                Entry<MemorySegment> entry = ssTablesIterator.next();
                buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
                indexOffset += Long.BYTES;
                buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, dataOffset, entry.key().byteSize());
                dataOffset += Long.BYTES;
                MemorySegment.copy(entry.key(), 0, buffer, dataOffset, entry.key().byteSize());
                dataOffset += entry.key().byteSize();
                buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
                indexOffset += Long.BYTES;
                buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, dataOffset, entry.value().byteSize());
                dataOffset += Long.BYTES;
                MemorySegment.copy(entry.value(), 0, buffer, dataOffset, entry.value().byteSize());
                dataOffset += entry.value().byteSize();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            stateNow.storage.readArena.close();
        }
    }

    private long getCompactionTableByteSize(State stateNow) {
        Iterator<Entry<MemorySegment>> dataIterator = stateNow.storage.range(Collections.emptyIterator(),
                Collections.emptyIterator(),
                null,
                null,
                MemorySegmentComparator.getMemorySegmentComparator());
        long compactionTableByteSize = 0;
        long countEntry = 0;
        while (dataIterator.hasNext()) {
            Entry<MemorySegment> entry = dataIterator.next();
            compactionTableByteSize += entry.key().byteSize();
            compactionTableByteSize += entry.value().byteSize();
            countEntry++;
        }
        ssTablesEntryQuantity = countEntry;
        return compactionTableByteSize + countEntry * 4L * Long.BYTES + Long.BYTES;
    }

    public static long sizeOf(final Entry<MemorySegment> entry) {
        if (entry == null) {
            return 0L;
        }

        if (entry.value() == null) {
            return entry.key().byteSize();
        }

        return entry.key().byteSize() + entry.value().byteSize();
    }
}
