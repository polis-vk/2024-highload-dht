package ru.vk.itmo.test.kislovdanil.dao.sstable;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

class Metadata {
    private final SSTable table;
    private final SSTable.Range keyRange;
    private final SSTable.Range valueRange;
    private final Boolean isDeletion;
    public static final long SIZE = Long.BYTES * 4 + 1;

    public Metadata(long index, SSTable table) {
        this.table = table;
        long base = index * Metadata.SIZE;
        keyRange = table.readRange(table.summaryFile, base);
        valueRange = table.readRange(table.summaryFile, base + 2 * Long.BYTES);
        isDeletion = table.summaryFile.get(ValueLayout.JAVA_BOOLEAN, base + 4 * Long.BYTES);
    }

    public MemorySegment readKey() {
        return table.indexFile.asSlice(keyRange.offset, keyRange.length);
    }

    public MemorySegment readValue() {
        return isDeletion ? null : table.dataFile.asSlice(valueRange.offset, valueRange.length);
    }

    public static void writeEntryMetadata(Entry<MemorySegment> entry, MemorySegment summaryFile,
                                          long sumOffset, long indexOffset, long dataOffset) {
        summaryFile.set(ValueLayout.JAVA_LONG_UNALIGNED,
                sumOffset, indexOffset);
        summaryFile.set(ValueLayout.JAVA_LONG_UNALIGNED,
                sumOffset + Long.BYTES, entry.key().byteSize());
        summaryFile.set(ValueLayout.JAVA_LONG_UNALIGNED,
                sumOffset + 2 * Long.BYTES, dataOffset);
        summaryFile.set(ValueLayout.JAVA_BOOLEAN,
                sumOffset + 4 * Long.BYTES, entry.value() == null);
        summaryFile.set(ValueLayout.JAVA_LONG_UNALIGNED,
                sumOffset + 3 * Long.BYTES, entry.value() == null ? 0 : entry.value().byteSize());
    }

}
