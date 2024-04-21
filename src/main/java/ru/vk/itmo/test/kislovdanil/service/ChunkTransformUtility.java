package ru.vk.itmo.test.kislovdanil.service;

import ru.vk.itmo.test.kislovdanil.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class ChunkTransformUtility {
    private static final String CHUNK_SEPARATOR = "\r\n";
    private static final String KEY_VALUE_SEPARATOR = "\n";
    public static final byte[] EMPTY_CONTENT = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] HEADERS = """
            HTTP/1.1 200 OK\r
            Transfer-Encoding: chunked\r
            \r
            """.getBytes(StandardCharsets.UTF_8);

    private ChunkTransformUtility() {
        
    }

    public static byte[] makeContent(Entry<MemorySegment> entry) {
        int entrySize = (int) (entry.key().byteSize() + entry.value().byteSize()) + KEY_VALUE_SEPARATOR.length();
        String entrySizeHex = Long.toHexString(entrySize);
        String content = entrySizeHex
                + CHUNK_SEPARATOR
                + new String(entry.key().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
                + KEY_VALUE_SEPARATOR
                + new String(entry.value().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
                + CHUNK_SEPARATOR;
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
