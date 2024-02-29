package ru.vk.itmo.test.kislovdanil.dao.sstable;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class Metadata {
    public static final long SIZE = Long.BYTES * 4 + 1;

    private Metadata() {
    }

    public static MemorySegment readKey(SSTable table, long index) {
        long base = index * Metadata.SIZE;
        long keyOffset = table.summaryFile.get(ValueLayout.JAVA_LONG_UNALIGNED, base);
        long keyLength = table.summaryFile.get(ValueLayout.JAVA_LONG_UNALIGNED, base + Long.BYTES);
        return table.indexFile.asSlice(keyOffset, keyLength);
    }

    public static MemorySegment readValue(SSTable table, long index) {
        long base = index * Metadata.SIZE;
        boolean isDeletion = table.summaryFile.get(ValueLayout.JAVA_BOOLEAN, base + 4 * Long.BYTES);
        if (isDeletion) return null;
        long valueOffset = table.summaryFile.get(ValueLayout.JAVA_LONG_UNALIGNED, base + 2 * Long.BYTES);
        long valueLength = table.summaryFile.get(ValueLayout.JAVA_LONG_UNALIGNED, base + 3 * Long.BYTES);
        return table.dataFile.asSlice(valueOffset, valueLength);
    }

    public static void writeEntryMetadata(Entry<MemorySegment> entry, MemorySegment summaryFile,
                                          long sumOffset, long indexOffset, long dataOffset) {
        summaryFile.set(ValueLayout.JAVA_LONG_UNALIGNED, sumOffset, indexOffset);
        summaryFile.set(ValueLayout.JAVA_LONG_UNALIGNED, sumOffset + Long.BYTES, entry.key().byteSize());
        summaryFile.set(ValueLayout.JAVA_LONG_UNALIGNED, sumOffset + 2 * Long.BYTES, dataOffset);
        summaryFile.set(ValueLayout.JAVA_BOOLEAN, sumOffset + 4 * Long.BYTES, entry.value() == null);
        summaryFile.set(ValueLayout.JAVA_LONG_UNALIGNED, sumOffset + 3 * Long.BYTES,
                entry.value() == null ? 0 : entry.value().byteSize());
    }

}
