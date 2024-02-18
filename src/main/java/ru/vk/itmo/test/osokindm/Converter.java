package ru.vk.itmo.test.osokindm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class Converter {

    public static MemorySegment getMemorySegment(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    public static MemorySegment getMemorySegment(byte[] data) {
        return MemorySegment.ofArray(data);
    }

    public static String getString(MemorySegment value) {
        return value == null ? null
                : new String((byte[]) value.heapBase()
                .orElse(value.toArray(ValueLayout.JAVA_BYTE)), StandardCharsets.UTF_8);
    }

}
