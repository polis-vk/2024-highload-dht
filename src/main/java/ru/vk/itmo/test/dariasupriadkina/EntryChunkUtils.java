package ru.vk.itmo.test.dariasupriadkina;

import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class EntryChunkUtils {

    private EntryChunkUtils() {
    }

    static final byte[] HEADER_BYTES =
            """
                    HTTP/1.1 200 OK\r
                    Content-Type: text/plain\r
                    Transfer-Encoding: chunked\r
                    Connection: keep-alive\r
                    \r
                    """.getBytes(StandardCharsets.UTF_8);
    static final byte[] LAST_BYTES = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIMITER_BYTES = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLRF_BYTES = "\r\n".getBytes(StandardCharsets.UTF_8);

    public static void getEntryByteChunk(Entry<MemorySegment> ee, ByteArrayBuilder bb) {
        byte[] key = getEntryKeyChunk(ee);
        byte[] value = getEntryValueChunk(ee);
        byte[] kvLength = getKVLengthChunk(ee);

        bb.append(kvLength, 0, kvLength.length);
        bb.append(CLRF_BYTES, 0, CLRF_BYTES.length);
        bb.append(key, 0, key.length);
        bb.append(DELIMITER_BYTES, 0, DELIMITER_BYTES.length);
        bb.append(value, 0, value.length);
        bb.append(CLRF_BYTES, 0, CLRF_BYTES.length);
    }

    public static byte[] getEntryKeyChunk(Entry<MemorySegment> entry) {
        return entry.key().toArray(ValueLayout.JAVA_BYTE);
    }

    public static byte[] getEntryValueChunk(Entry<MemorySegment> entry) {
        return entry.value().toArray(ValueLayout.JAVA_BYTE);
    }

    public static byte[] getKVLengthChunk(Entry<MemorySegment> entry) {
        return Long.toHexString((entry.value().byteSize()
                + entry.key().byteSize() + DELIMITER_BYTES.length)).getBytes(StandardCharsets.UTF_8);
    }

}
