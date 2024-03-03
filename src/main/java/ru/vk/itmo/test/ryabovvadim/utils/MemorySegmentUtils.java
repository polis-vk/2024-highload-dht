package ru.vk.itmo.test.ryabovvadim.utils;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

public final class MemorySegmentUtils {

    public static MemorySegment fromString(String value) {
        if (value == null) {
            return null;
        }
        return MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8));
    }

    public static MemorySegment fromBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return MemorySegment.ofArray(bytes);
    }

    private MemorySegmentUtils() {
    }
}
