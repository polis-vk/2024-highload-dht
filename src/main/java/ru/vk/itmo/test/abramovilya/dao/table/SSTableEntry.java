package ru.vk.itmo.test.abramovilya.dao.table;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class SSTableEntry implements TableEntry {
    private final int number;
    private final MemorySegment mappedStorageFile;
    private final SSTable ssTable;
    private final long storageFileOffset;
    private final MemorySegment currentKey;

    public SSTableEntry(int number, long storageFileOffset, MemorySegment mappedStorageFile, SSTable ssTable) {
        this.number = number;
        this.storageFileOffset = storageFileOffset;
        this.mappedStorageFile = mappedStorageFile;
        this.ssTable = ssTable;

        long msSize = mappedStorageFile.get(ValueLayout.JAVA_LONG_UNALIGNED, storageFileOffset);
        currentKey = mappedStorageFile.asSlice(storageFileOffset + Long.BYTES, msSize);
    }

    @Override
    public MemorySegment key() {
        return currentKey;
    }

    @Override
    public MemorySegment value() {
        long valueOffset = storageFileOffset + Long.BYTES + currentKey.byteSize();
        long valueSize = mappedStorageFile.get(ValueLayout.JAVA_LONG_UNALIGNED, valueOffset);
        if (valueSize == -1) {
            return null;
        }
        valueOffset += Long.BYTES;
        return mappedStorageFile.asSlice(valueOffset, valueSize);
    }

    @Override
    public int number() {
        return number;
    }

    @Override
    public Table table() {
        return ssTable;
    }
}
