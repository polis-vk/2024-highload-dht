package ru.vk.itmo.test.solnyshkoksenia.dao.storage;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.solnyshkoksenia.dao.EntryExtended;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;

public class StorageUtils {
    protected MemorySegment slice(MemorySegment page, long start, long end) {
        return page.asSlice(start, end - start);
    }

    protected long startOfKey(MemorySegment segment, long recordIndex) {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, recordIndex * 4 * Long.BYTES);
    }

    protected long endOfKey(MemorySegment segment, long recordIndex) {
        return normalizedStartOfValue(segment, recordIndex);
    }

    protected long startOfValue(MemorySegment segment, long recordIndex) {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, recordIndex * 4 * Long.BYTES + Long.BYTES);
    }

    protected long endOfValue(MemorySegment segment, long recordIndex) {
        return normalizedStartOfTimestamp(segment, recordIndex);
    }

    protected long startOfTimestamp(MemorySegment segment, long recordIndex) {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, recordIndex * 4 * Long.BYTES + Long.BYTES * 2);
    }

    protected long endOfTimestamp(MemorySegment segment, long recordIndex) {
        return normalizedStartOfExpiration(segment, recordIndex);
    }

    protected long startOfExpiration(MemorySegment segment, long recordIndex) {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, recordIndex * 4 * Long.BYTES + Long.BYTES * 3);
    }

    protected long endOfExpiration(MemorySegment segment, long recordIndex, long recordsCount) {
        if (recordIndex < recordsCount - 1) {
            return startOfKey(segment, recordIndex + 1);
        }
        return segment.byteSize();
    }

    protected long tombstone(long offset) {
        return 1L << 63 | offset;
    }

    protected long normalize(long value) {
        return value & ~(1L << 63);
    }

    protected long recordsCount(MemorySegment segment) {
        long indexSize = indexSize(segment);
        return indexSize / Long.BYTES / 4;
    }

    protected MemorySegment mapFile(FileChannel fileChannel, long size, Arena arena) throws IOException {
        return fileChannel.map(
                FileChannel.MapMode.READ_WRITE,
                0,
                size,
                arena
        );
    }

    protected Entry<Long> putEntry(MemorySegment fileSegment, Entry<Long> offsets, EntryExtended<MemorySegment> entry) {
        long dataOffset = offsets.key();
        long indexOffset = offsets.value();

        fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
        indexOffset += Long.BYTES;
        MemorySegment key = entry.key();
        MemorySegment.copy(key, 0, fileSegment, dataOffset, key.byteSize());
        dataOffset += key.byteSize();

        MemorySegment value = entry.value();
        if (value == null) {
            fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, tombstone(dataOffset));
        } else {
            fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
            MemorySegment.copy(value, 0, fileSegment, dataOffset, value.byteSize());
            dataOffset += value.byteSize();
        }
        indexOffset += Long.BYTES;

        fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
        indexOffset += Long.BYTES;
        MemorySegment timestamp = entry.timestamp();
        MemorySegment.copy(timestamp, 0, fileSegment, dataOffset, timestamp.byteSize());
        dataOffset += timestamp.byteSize();

        MemorySegment expiration = entry.expiration();
        if (expiration == null) {
            fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, tombstone(dataOffset));
        } else {
            fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
            MemorySegment.copy(expiration, 0, fileSegment, dataOffset, expiration.byteSize());
            dataOffset += expiration.byteSize();
        }
        indexOffset += Long.BYTES;

        return new BaseEntry<>(dataOffset, indexOffset);
    }

    private long normalizedStartOfValue(MemorySegment segment, long recordIndex) {
        return normalize(startOfValue(segment, recordIndex));
    }

    private long normalizedStartOfTimestamp(MemorySegment segment, long recordIndex) {
        return normalize(startOfTimestamp(segment, recordIndex));
    }

    private long normalizedStartOfExpiration(MemorySegment segment, long recordIndex) {
        return normalize(startOfExpiration(segment, recordIndex));
    }

    private static long indexSize(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 0);
    }

    protected long indexOf(MemorySegment segment, MemorySegment key) {
        long recordsCount = recordsCount(segment);

        long left = 0;
        long right = recordsCount - 1;
        while (left <= right) {
            long mid = (left + right) >>> 1;

            long startOfKey = startOfKey(segment, mid);
            long endOfKey = endOfKey(segment, mid);
            long mismatch = MemorySegment.mismatch(segment, startOfKey, endOfKey, key, 0, key.byteSize());
            if (mismatch == -1) {
                return mid;
            }

            if (mismatch == key.byteSize()) {
                right = mid - 1;
                continue;
            }

            if (mismatch == endOfKey - startOfKey) {
                left = mid + 1;
                continue;
            }

            int b1 = Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, startOfKey + mismatch));
            int b2 = Byte.toUnsignedInt(key.get(ValueLayout.JAVA_BYTE, mismatch));
            if (b1 > b2) {
                right = mid - 1;
            } else {
                left = mid + 1;
            }
        }

        return tombstone(left);
    }

    protected boolean checkTTL(MemorySegment expiration, long time) {
        return expiration.get(ValueLayout.JAVA_LONG_UNALIGNED, 0) > time;
    }
}
