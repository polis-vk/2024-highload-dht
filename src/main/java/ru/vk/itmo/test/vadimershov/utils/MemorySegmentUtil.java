package ru.vk.itmo.test.vadimershov.utils;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;

import javax.annotation.Nonnull;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class MemorySegmentUtil {

    public static MemorySegment toMemorySegment(@Nonnull String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    public static Entry<MemorySegment> toDeletedEntity(@Nonnull String key) {
        return new BaseEntry<>(toMemorySegment(key), null);
    }

    public static Entry<MemorySegment> toEntity(@Nonnull String key, @Nonnull byte[] value) {
        return new BaseEntry<>(toMemorySegment(key), MemorySegment.ofArray(value));
    }

    public static byte[] toByteArray(@Nonnull MemorySegment data) {
        return data.toArray(ValueLayout.JAVA_BYTE);
    }

}
