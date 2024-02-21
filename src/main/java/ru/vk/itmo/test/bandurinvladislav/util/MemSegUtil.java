package ru.vk.itmo.test.bandurinvladislav.util;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class MemSegUtil {
    private MemSegUtil() {
        throw new UnsupportedOperationException();
    }

    public static String toString(MemorySegment memorySegment) {
        return memorySegment == null ? null :
                new String(memorySegment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    public static MemorySegment fromByteArray(byte[] data) {
        return data == null ? null : MemorySegment.ofArray(data);
    }

    public static MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    public static Entry<MemorySegment> fromBaseEntry(Entry<MemorySegment> baseEntry) {
        return baseEntry;
    }
}
