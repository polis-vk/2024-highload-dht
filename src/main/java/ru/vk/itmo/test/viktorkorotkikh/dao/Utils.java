package ru.vk.itmo.test.viktorkorotkikh.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;

public final class Utils {

    private Utils() {
    }

    public static long getEntrySize(Entry<MemorySegment> entry) {
        if (entry.value() == null) {
            return Long.BYTES + entry.key().byteSize() + Long.BYTES + Long.BYTES;
        }
        return Long.BYTES + entry.key().byteSize() + Long.BYTES + entry.value().byteSize() + Long.BYTES;
    }
}
