package ru.vk.itmo.test.tyapuevdmitrij.dao;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class Storage {
    protected final StorageHelper storageHelper;
    protected int ssTablesQuantity;
    protected List<MemorySegment> ssTables;
    protected final Arena readArena;

    public Storage(Path ssTablePath) {
        storageHelper = new StorageHelper();
        ssTablesQuantity = storageHelper.findSsTablesQuantity(ssTablePath);
        ssTables = new ArrayList<>(ssTablesQuantity);
        readArena = Arena.ofShared();
        for (int i = 0; i < ssTablesQuantity; i++) {
            Path path = ssTablePath.resolve(StorageHelper.SS_TABLE_FILE_NAME + i);
            ssTables.add(NmapBuffer.getReadBufferFromSsTable(path, readArena));
        }
    }

    public Storage(StorageHelper storageHelper, int ssTablesQuantity, List<MemorySegment> ssTables, Arena readArena) {
        this.storageHelper = storageHelper;
        this.ssTablesQuantity = ssTablesQuantity;
        this.ssTables = ssTables;
        this.readArena = readArena;
    }

    public void save(Collection<Entry<MemorySegment>> memTableEntries,
                     Path ssTablePath,
                     Storage prevState) throws IOException {
        if (memTableEntries.isEmpty()) {
            return;
        }
        Path path = ssTablePath.resolve(StorageHelper.TEMP_SS_TABLE_FILE_NAME + prevState.ssTablesQuantity);
        try (Arena writeArena = Arena.ofConfined()) {
            MemorySegment buffer;
            buffer = NmapBuffer.getWriteBufferToSsTable(prevState.storageHelper.getSsTableDataByteSize(memTableEntries),
                    path, writeArena);
            prevState.writeMemTableDataToFile(buffer, memTableEntries, prevState);
        }
        Path newPath = ssTablePath.resolve(StorageHelper.SS_TABLE_FILE_NAME + prevState.ssTablesQuantity);
        try {
            Files.move(
                    path,
                    newPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException e) {
            throw new DAOException("Can't rename file", e);
        }
    }

    private void writeMemTableDataToFile(MemorySegment buffer,
                                         Iterable<Entry<MemorySegment>> memTableEntries,
                                         Storage prevState) {
        long offset = 0;
        long bufferByteSize = buffer.byteSize();
        long writeIndexPosition;
        writeIndexPosition = bufferByteSize - prevState.storageHelper.memTableEntriesCount * 2L * Long.BYTES
                - Long.BYTES;
        //write to the end of file size of memTable
        buffer.set(ValueLayout.JAVA_LONG_UNALIGNED,
                bufferByteSize - Long.BYTES,
                prevState.storageHelper.memTableEntriesCount);
        for (Entry<MemorySegment> entry : memTableEntries) {
            buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, entry.key().byteSize());
            //  write keyByteSizeOffsetPosition to the end of buffer
            buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, writeIndexPosition, offset);
            writeIndexPosition += Long.BYTES;
            offset += Long.BYTES;
            MemorySegment.copy(entry.key(), 0, buffer, offset, entry.key().byteSize());
            offset += entry.key().byteSize();
            //  write valueByteSizeOffsetPosition to the end of buffer next to the keyByteSizeOffsetPosition
            buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, writeIndexPosition, offset);
            writeIndexPosition += Long.BYTES;
            if (entry.value() == null) {
                buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, tombstone(offset));
                offset += Long.BYTES;
            } else {
                buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, entry.value().byteSize());
                offset += Long.BYTES;
                MemorySegment.copy(entry.value(), 0, buffer, offset, entry.value().byteSize());
                offset += entry.value().byteSize();
            }
            // write timeStamp next to the value
            buffer.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, entry.timeStamp());
            offset += Long.BYTES;
        }
    }

    private long getSsTableIndexByKey(MemorySegment ssTable, MemorySegment key,
                                      Comparator<MemorySegment> memorySegmentComparator) {
        long memTableSize = ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, ssTable.byteSize() - Long.BYTES);
        long left = 0;
        long right = memTableSize - 1L;
        long lastKeyIndexOffset = ssTable.byteSize() - 3 * Long.BYTES;
        while (left <= right) {
            long mid = (left + right) >>> 1;
            long midOffset = lastKeyIndexOffset - (memTableSize - 1L) * Long.BYTES * 2L + mid * 2L * Long.BYTES;
            MemorySegment readKey = getKeyByOffset(ssTable, midOffset);
            int res = memorySegmentComparator.compare(readKey, key);
            if (res == 0) {
                return mid;
            }
            if (res > 0) {
                right = mid - 1;
            } else {
                left = mid + 1;
            }
        }
        return left;
    }

    private Entry<MemorySegment> getSsTableEntryByIndex(MemorySegment ssTable, long index) {
        long memTableSize = ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, ssTable.byteSize() - Long.BYTES);
        if (index > memTableSize - 1 || index < 0) {
            return null;
        }
        long keyIndexOffset = ssTable.byteSize() - 3 * Long.BYTES
                - (memTableSize - 1L) * Long.BYTES * 2L + index * 2L * Long.BYTES;
        MemorySegment readKey = getKeyByOffset(ssTable, keyIndexOffset);
        MemorySegment readValue = getValueByOffset(ssTable, keyIndexOffset + Long.BYTES);
        long timeStamp = getTimeStampByOffset(ssTable, keyIndexOffset + Long.BYTES);
        return new BaseEntry<>(readKey, readValue, timeStamp);
    }

    private MemorySegment getKeyByOffset(MemorySegment ssTable, long offset) {
        long keyByteSizeOffset = ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
        long keyByteSize = ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, keyByteSizeOffset);
        long keyOffset = keyByteSizeOffset + Long.BYTES;
        return ssTable.asSlice(keyOffset, keyByteSize);
    }

    private MemorySegment getValueByOffset(MemorySegment ssTable, long offset) {
        long valueByteSizeOffset = ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
        long valueByteSize = ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, valueByteSizeOffset);
        if (valueByteSize < 0) {
            return null;
        }
        long valueOffset = valueByteSizeOffset + Long.BYTES;
        return ssTable.asSlice(valueOffset, valueByteSize);
    }

    private long getTimeStampByOffset(MemorySegment ssTable, long offset) {
        long valueByteSizeOffset = ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
        long valueByteSize = ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, valueByteSizeOffset);
        if (valueByteSize < 0) {
            return ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, valueByteSizeOffset + Long.BYTES);
        }
        return ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, valueByteSizeOffset + Long.BYTES + valueByteSize);
    }

    public Iterator<Entry<MemorySegment>> range(
            Iterator<Entry<MemorySegment>> flushMemTableIterator,
            Iterator<Entry<MemorySegment>> memTableIterator,
            MemorySegment from,
            MemorySegment to, Comparator<MemorySegment> memorySegmentComparator) {
        List<Iterator<Entry<MemorySegment>>> iterators = new ArrayList<>(ssTablesQuantity + 2);
        for (MemorySegment memorySegment : ssTables) {
            iterators.add(iterator(memorySegment, from, to, memorySegmentComparator));
        }
        iterators.add(flushMemTableIterator);
        iterators.add(memTableIterator);
        return new MergeIterator(iterators, Comparator.comparing(Entry::key, memorySegmentComparator));
    }

    private Iterator<Entry<MemorySegment>> iterator(MemorySegment ssTable, MemorySegment from, MemorySegment to,
                                                    Comparator<MemorySegment> memorySegmentComparator) {
        long recordIndexFrom = from == null ? 0 : normalize(getSsTableIndexByKey(ssTable,
                from,
                memorySegmentComparator));
        long memTableSize = ssTable.get(ValueLayout.JAVA_LONG_UNALIGNED, ssTable.byteSize() - Long.BYTES);
        long recordIndexTo = to == null ? memTableSize : normalize(getSsTableIndexByKey(ssTable,
                to,
                memorySegmentComparator));

        return new Iterator<>() {
            long index = recordIndexFrom;

            @Override
            public boolean hasNext() {
                return index < recordIndexTo;
            }

            @Override
            public Entry<MemorySegment> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Entry<MemorySegment> entry = getSsTableEntryByIndex(ssTable, index);
                index++;
                return entry;
            }
        };
    }

    private static long tombstone(long offset) {
        return 1L << 63 | offset;
    }

    private static long normalize(long value) {
        return value & ~(1L << 63);
    }

}
