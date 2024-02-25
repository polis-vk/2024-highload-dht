package ru.vk.itmo.test.proninvalentin;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class MemorySegmentFactory {
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

    public static Entry<MemorySegment> toMemorySegment(String key, String value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException();
        }

        MemorySegment msKey = fromString(key);
        MemorySegment msValue = fromString(value);
        return new BaseEntry<>(msKey, msValue);
    }

    public static Entry<MemorySegment> toDeletedMemorySegment(String key) {
        if (key == null) {
            throw new IllegalArgumentException();
        }

        MemorySegment msKey = fromString(key);
        return new BaseEntry<>(msKey, null);
    }
}

