package ru.vk.itmo.test.kovalchukvladislav.dao.model;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Iterator;
import java.util.NoSuchElementException;


public class StorageIterator<D, E extends Entry<D>> implements Iterator<E> {
    private final EntryExtractor<D, E> extractor;
    private final MemorySegment storageSegment;
    private final long end;
    private long start;

    public StorageIterator(D from, D to,
                           MemorySegment storageSegment,
                           MemorySegment offsetsSegment,
                           EntryExtractor<D, E> extractor) {
        this.storageSegment = storageSegment;
        this.extractor = extractor;

        if (offsetsSegment.byteSize() == 0) {
            this.start = -1;
            this.end = -1;
        } else {
            this.start = calculateStartPosition(offsetsSegment, from);
            this.end = calculateEndPosition(offsetsSegment, to);
        }
    }

    private long calculateStartPosition(MemorySegment offsetsSegment, D from) {
        if (from == null) {
            return getFirstOffset(offsetsSegment);
        }
        long lowerBoundOffset = extractor.findLowerBoundValueOffset(from, storageSegment, offsetsSegment);
        if (lowerBoundOffset == -1) {
            // the smallest element and doesn't exist
            return getFirstOffset(offsetsSegment);
        } else {
            // storage[lowerBoundOffset] <= from, we need >= only
            return moveOffsetIfFirstKeyAreNotEqual(from, lowerBoundOffset);
        }
    }

    private long calculateEndPosition(MemorySegment offsetsSegment, D to) {
        if (to == null) {
            return getEndOffset();
        }
        long lowerBoundOffset = extractor.findLowerBoundValueOffset(to, storageSegment, offsetsSegment);
        if (lowerBoundOffset == -1) {
            // the smallest element and doesn't exist
            return getFirstOffset(offsetsSegment);
        }
        // storage[lowerBoundOffset] <= to, we need >= only
        return moveOffsetIfFirstKeyAreNotEqual(to, lowerBoundOffset);
    }

    private long getFirstOffset(MemorySegment offsetsSegment) {
        return offsetsSegment.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, 0);
    }

    private long getEndOffset() {
        return storageSegment.byteSize();
    }

    private long moveOffsetIfFirstKeyAreNotEqual(D from, long lowerBoundOffset) {
        long offset = lowerBoundOffset;
        D lowerBoundKey = extractor.readValue(storageSegment, offset);
        if (extractor.compare(lowerBoundKey, from) != 0) {
            offset += extractor.size(lowerBoundKey);
            D lowerBoundValue = extractor.readValue(storageSegment, offset);
            offset += extractor.size(lowerBoundValue);
        }
        return offset;
    }

    @Override
    public boolean hasNext() {
        return start < end;
    }

    @Override
    public E next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        E entry = extractor.readEntry(storageSegment, start);
        start += extractor.size(entry);
        return entry;
    }
}
