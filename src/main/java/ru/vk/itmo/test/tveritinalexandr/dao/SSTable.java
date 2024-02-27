package ru.vk.itmo.test.tveritinalexandr.dao;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Persistent SSTable in data file and index file.
 *
 * @author incubos
 * @see SSTables
 */
final class SSTable {
    final int sequence;

    private final MemorySegment index;
    private final MemorySegment data;
    private final long size;

    SSTable(
            final int sequence,
            final MemorySegment index,
            final MemorySegment data) {
        this.sequence = sequence;
        this.index = index;
        this.data = data;
        this.size = index.byteSize() / Long.BYTES;
    }

    SSTable withSequence(final int sequence) {
        return new SSTable(
                sequence,
                index,
                data);
    }

    /**
     * Returns index of the entry if found; otherwise, (-(insertion point) - 1).
     * The insertion point is defined as the point at which the key would be inserted:
     * the index of the first element greater than the key,
     * or size if all keys are less than the specified key.
     * Note that this guarantees that the return value will be >= 0
     * if and only if the key is found.
     */
    private long entryBinarySearch(final MemorySegment key) {
        long low = 0L;
        long high = size - 1;

        while (low <= high) {
            final long mid = (low + high) >>> 1;
            final long midEntryOffset = entryOffset(mid);
            final long midKeyLength = getLength(midEntryOffset);
            final int compare =
                    MemorySegmentComparator.compare(
                            data,
                            midEntryOffset + Long.BYTES, // Position at key
                            midKeyLength,
                            key,
                            0L,
                            key.byteSize());

            if (compare < 0) {
                low = mid + 1;
            } else if (compare > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        return -(low + 1);
    }

    private long entryOffset(final long entry) {
        return index.get(
                ValueLayout.OfLong.JAVA_LONG,
                entry * Long.BYTES);
    }

    private long getLength(final long offset) {
        return data.get(
                ValueLayout.OfLong.JAVA_LONG_UNALIGNED,
                offset);
    }

    Iterator<Entry<MemorySegment>> get(
            final MemorySegment from,
            final MemorySegment to) {
        assert from == null || to == null || MemorySegmentComparator.INSTANCE.compare(from, to) <= 0;

        // Slice of SSTable in absolute offsets
        final long fromOffset;
        final long toOffset;

        // Left offset bound
        if (from == null) {
            // Start from the beginning
            fromOffset = 0L;
        } else {
            final long fromEntry = entryBinarySearch(from);
            if (fromEntry >= 0L) {
                fromOffset = entryOffset(fromEntry);
            } else if (-fromEntry - 1 == size) {
                // No relevant data
                return Collections.emptyIterator();
            } else {
                // Greater but existing key found
                fromOffset = entryOffset(-fromEntry - 1);
            }
        }

        // Right offset bound
        if (to == null) {
            // Up to the end
            toOffset = data.byteSize();
        } else {
            final long toEntry = entryBinarySearch(to);
            if (toEntry >= 0L) {
                toOffset = entryOffset(toEntry);
            } else if (-toEntry - 1 == size) {
                // Up to the end
                toOffset = data.byteSize();
            } else {
                // Greater but existing key found
                toOffset = entryOffset(-toEntry - 1);
            }
        }

        return new SliceIterator(fromOffset, toOffset);
    }

    Entry<MemorySegment> get(final MemorySegment key) {
        final long entry = entryBinarySearch(key);
        if (entry < 0) {
            return null;
        }

        // Skip key (will reuse the argument)
        long offset = entryOffset(entry);
        offset += Long.BYTES + key.byteSize();
        // Extract value length
        final long valueLength = getLength(offset);
        if (valueLength == SSTables.TOMBSTONE_VALUE_LENGTH) {
            // Tombstone encountered
            return new BaseEntry<>(key, null);
        } else {
            // Get value
            offset += Long.BYTES;
            final MemorySegment value = data.asSlice(offset, valueLength);
            return new BaseEntry<>(key, value);
        }
    }

    private final class SliceIterator implements Iterator<Entry<MemorySegment>> {
        private long offset;
        private final long toOffset;

        private SliceIterator(
                final long offset,
                final long toOffset) {
            this.offset = offset;
            this.toOffset = toOffset;
        }

        @Override
        public boolean hasNext() {
            return offset < toOffset;
        }

        @Override
        public Entry<MemorySegment> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            // Read key length
            final long keyLength = getLength(offset);
            offset += Long.BYTES;

            // Read key
            final MemorySegment key = data.asSlice(offset, keyLength);
            offset += keyLength;

            // Read value length
            final long valueLength = getLength(offset);
            offset += Long.BYTES;

            // Read value
            if (valueLength == SSTables.TOMBSTONE_VALUE_LENGTH) {
                // Tombstone encountered
                return new BaseEntry<>(key, null);
            } else {
                final MemorySegment value = data.asSlice(offset, valueLength);
                offset += valueLength;
                return new BaseEntry<>(key, value);
            }
        }
    }
}
