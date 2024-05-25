package ru.vk.itmo.test.proninvalentin.utils;

import ru.vk.itmo.test.proninvalentin.dao.ExtendedBaseEntry;
import ru.vk.itmo.test.proninvalentin.dao.ExtendedEntry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class MemorySegmentFactory {
    private MemorySegmentFactory() {
        // Suppress warning
    }

    public static byte[] toByteArray(MemorySegment data) {
        if (data == null) {
            throw new IllegalArgumentException();
        }

        return data.toArray(ValueLayout.JAVA_BYTE);
    }

    public static MemorySegment fromString(String data) {
        if (data == null) {
            throw new IllegalArgumentException();
        }

        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    public static ExtendedEntry<MemorySegment> toDeletedMemorySegment(String key, long timestamp) {
        if (key == null) {
            throw new IllegalArgumentException();
        }

        MemorySegment msKey = fromString(key);
        return new ExtendedBaseEntry<>(msKey, null, timestamp);
    }
}

