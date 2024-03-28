package ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable;

import ru.vk.itmo.test.smirnovdmitrii.dao.TimeEntry;

import java.io.Closeable;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Representation of opened SSTable. You can read from opened SSTable.
 */
public class OpenedSSTable extends AbstractSSTable implements Closeable {

    OpenedSSTable(final MemorySegment mapped, final Path path, final long priority) {
        super(mapped, path, priority);
    }

    @Override
    public void close() {
        mapped = null;
    }

    public TimeEntry<MemorySegment> readBlock(final long index) {
        return new TimeEntry<>(
                readBlockTimestamp(index),
                readBlockKey(index),
                readBlockValue(index)
        );
    }

    private long readBlockTimestamp(final long index) {
        return mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, index * Long.BYTES * 3);
    }

    public MemorySegment readBlockKey(final long index) {
        final long startOfKey = startOfKey(index);
        return mapped.asSlice(startOfKey, endOfKey(index) - startOfKey);
    }

    private MemorySegment readBlockValue(final long index) {
        final long startOfValue = startOfValue(index);
        if (startOfValue < 0) {
            return null;
        }
        return mapped.asSlice(startOfValue, endOfValue(index) - startOfValue);
    }

    private long startOfKey(final long index) {
        return mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, index * Long.BYTES * 3 + Long.BYTES);
    }

    private long startOfValue(final long index) {
        return mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, index * Long.BYTES * 3 + 2 * Long.BYTES);
    }

    private long normalizedStartOfValue(final long index) {
        return SSTableUtil.normalize(startOfValue(index));
    }

    private long endOfKey(final long index) {
        return normalizedStartOfValue(index);
    }

    private long endOfValue(final long index) {
        if (index == blockCount() - 1) {
            return mapped.byteSize();
        }
        return startOfKey(index + 1);
    }

    public long blockCount() {
        return mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, Long.BYTES) / Long.BYTES / 3;
    }

    /**
     * Searching order number in ssTable for block with {@code key} using helping file with ssTable offsets.
     * If there is no block with such key, returns -(insert position + 1).
     * {@code offsets}.
     *
     * @param key searching key.
     * @return offset in sstable for key block.
     */
    public long binarySearch(
            final MemorySegment key,
            final Comparator<MemorySegment> comparator
    ) {
        long left = -1;
        long right = blockCount();
        while (left < right - 1) {
            long midst = (left + right) >>> 1;
            final MemorySegment currentKey = readBlockKey(midst);
            final int compareResult = comparator.compare(key, currentKey);
            if (compareResult == 0) {
                return midst;
            } else if (compareResult > 0) {
                left = midst;
            } else {
                right = midst;
            }
        }
        return SSTableUtil.tombstone(right);
    }

    public long upperBound(
            final MemorySegment key,
            final Comparator<MemorySegment> comparator
    ) {
        final long result = binarySearch(key, comparator);
        return SSTableUtil.normalize(result);
    }

}
