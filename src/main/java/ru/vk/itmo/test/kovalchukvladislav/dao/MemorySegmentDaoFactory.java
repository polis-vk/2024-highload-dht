package ru.vk.itmo.test.kovalchukvladislav.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class MemorySegmentDaoFactory {
    private static final Charset CHARSET = StandardCharsets.UTF_8;
    private static final ValueLayout.OfByte VALUE_LAYOUT = ValueLayout.JAVA_BYTE;

    private MemorySegmentDaoFactory() {
    }

    public static Dao<MemorySegment, Entry<MemorySegment>> createDao(Config config) throws IOException {
        return new MemorySegmentDao(config);
    }

    public static String toString(MemorySegment memorySegment) {
        if (memorySegment == null) {
            return null;
        }
        return new String(memorySegment.toArray(VALUE_LAYOUT), CHARSET);
    }

    public static MemorySegment fromString(String data) {
        if (data == null) {
            return null;
        }
        return MemorySegment.ofArray(data.getBytes(CHARSET));
    }

    public static Entry<MemorySegment> fromBaseEntry(Entry<MemorySegment> baseEntry) {
        return baseEntry;
    }
}
