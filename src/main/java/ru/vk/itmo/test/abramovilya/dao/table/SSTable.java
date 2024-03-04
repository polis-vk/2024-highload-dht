package ru.vk.itmo.test.abramovilya.dao.table;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class SSTable implements Table {
    private final int number;
    private final MemorySegment mappedIndexFile;
    private final MemorySegment mappedStorageFile;
    private long indexFileOffset;

    public SSTable(int number, long indexFileOffset, MemorySegment mappedStorageFile, MemorySegment mappedIndexFile) {
        this.number = number;
        this.indexFileOffset = indexFileOffset;
        this.mappedStorageFile = mappedStorageFile;
        this.mappedIndexFile = mappedIndexFile;
    }

    @Override
    public SSTableEntry nextEntry() {
        indexFileOffset += Integer.BYTES + Long.BYTES;
        if (indexFileOffset >= mappedIndexFile.byteSize()) {
            return null;
        }
        long storageOffset = mappedIndexFile.get(ValueLayout.JAVA_LONG_UNALIGNED, indexFileOffset);

        return new SSTableEntry(number, storageOffset, mappedStorageFile, this);
    }

    @Override
    public TableEntry currentEntry() {
        long storageOffset = mappedIndexFile.get(ValueLayout.JAVA_LONG_UNALIGNED, indexFileOffset);
        return new SSTableEntry(number, storageOffset, mappedStorageFile, this);
    }
}
