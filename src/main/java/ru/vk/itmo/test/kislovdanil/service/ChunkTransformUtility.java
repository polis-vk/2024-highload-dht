package ru.vk.itmo.test.kislovdanil.service;

import one.nio.http.HttpSession;
import ru.vk.itmo.test.kislovdanil.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ChunkTransformUtility {
    private static final byte[] CHUNK_SEPARATOR = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_VALUE_SEPARATOR = "\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EMPTY_CONTENT = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] HEADERS = """
            HTTP/1.1 200 OK\r
            Transfer-Encoding: chunked\r
            \r
            """.getBytes(StandardCharsets.UTF_8);

    private ChunkTransformUtility() {

    }

    private static void writeFull(byte[] data, HttpSession session) throws IOException {
        session.write(data, 0, data.length);
    }

    public static void writeContent(Entry<MemorySegment> entry, HttpSession session) throws IOException {
        int entrySize = (int) (entry.key().byteSize() + entry.value().byteSize()) + KEY_VALUE_SEPARATOR.length;
        byte[] entrySizeHex = Long.toHexString(entrySize).getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[entrySize + entrySizeHex.length + CHUNK_SEPARATOR.length * 2];
        ByteBuffer buffer = ByteBuffer.wrap(content);
        buffer.put(entrySizeHex)
                .put(CHUNK_SEPARATOR)
                .put(entry.key().toArray(ValueLayout.JAVA_BYTE))
                .put(KEY_VALUE_SEPARATOR)
                .put(entry.value().toArray(ValueLayout.JAVA_BYTE))
                .put(CHUNK_SEPARATOR);
        writeFull(content, session);
    }
}
