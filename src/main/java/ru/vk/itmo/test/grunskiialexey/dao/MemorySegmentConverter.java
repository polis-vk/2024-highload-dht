package ru.vk.itmo.test.grunskiialexey.dao;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class MemorySegmentConverter {
    public static String toString(MemorySegment memorySegment) {
        // :TODO check performance with default StandardCharsets
        return memorySegment == null
                ? null
                : new String(memorySegment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    public static MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }
}
