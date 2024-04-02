package ru.vk.itmo.test.vadimershov.utils;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.vadimershov.dao.TimestampBaseEntry;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;

import javax.annotation.Nonnull;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class MemorySegmentUtil {

    private MemorySegmentUtil() {
    }

    public static MemorySegment toMemorySegment(@Nonnull String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    public static TimestampEntry<MemorySegment> toDeletedEntity(@Nonnull String key, @Nonnull Long timestamp) {
        return new TimestampBaseEntry<>(toMemorySegment(key), null, timestamp);
    }

    public static TimestampEntry<MemorySegment> toEntity(@Nonnull String key, @Nonnull byte[] value, @Nonnull Long timestamp) {
        return new TimestampBaseEntry<>(toMemorySegment(key), MemorySegment.ofArray(value), timestamp);
    }

    public static byte[] toByteArray(@Nonnull MemorySegment data) {
        return data.toArray(ValueLayout.JAVA_BYTE);
    }

}
