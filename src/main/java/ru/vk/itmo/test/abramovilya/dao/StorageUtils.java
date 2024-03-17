package ru.vk.itmo.test.abramovilya.dao;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class StorageUtils {
    private StorageUtils() {
    }

    static int upperBound(MemorySegment key,
                                  MemorySegment storageMapped,
                                  MemorySegment indexMapped,
                                  long indexSize) {
        int l = -1;
        int r = indexMapped.get(ValueLayout.JAVA_INT_UNALIGNED, indexSize - Long.BYTES - Integer.BYTES);

        while (r - l > 1) {
            int m = (r + l) / 2;
            long keyStorageOffset = getKeyStorageOffset(indexMapped, m);
            long keySize = storageMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, keyStorageOffset);
            keyStorageOffset += Long.BYTES;

            if (DaoImpl.compareMemorySegmentsUsingOffset(key, storageMapped, keyStorageOffset, keySize) > 0) {
                l = m;
            } else {
                r = m;
            }
        }
        return r;
    }

    static long getKeyStorageOffset(MemorySegment indexMapped, int entryNum) {
        return indexMapped.get(
                ValueLayout.JAVA_LONG_UNALIGNED,
                (long) (Integer.BYTES + Long.BYTES) * entryNum + Integer.BYTES
        );
    }
}
