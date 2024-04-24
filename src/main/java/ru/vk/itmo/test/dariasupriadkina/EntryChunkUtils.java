package ru.vk.itmo.test.dariasupriadkina;

import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class EntryChunkUtils {

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
        bb.append(EntryChunkUtils.getKVLengthChunk(ee), 0, EntryChunkUtils.getKVLengthChunk(ee).length);
        bb.append(CLRF_BYTES, 0, CLRF_BYTES.length);
        bb.append(EntryChunkUtils.getEntryKeyChunk(ee), 0, EntryChunkUtils.getEntryKeyChunk(ee).length);
        bb.append(DELIMITER_BYTES, 0, DELIMITER_BYTES.length);
        bb.append(EntryChunkUtils.getEntryValueChunk(ee), 0, EntryChunkUtils.getEntryValueChunk(ee).length);
        bb.append(CLRF_BYTES, 0, CLRF_BYTES.length);
    }

    public static byte[] getEntryKeyChunk(Entry<MemorySegment> entry) {
        return entry.key().toArray(ValueLayout.JAVA_BYTE);
    }

    public static byte[] getEntryValueChunk(Entry<MemorySegment> entry) {
        return entry.value().toArray(ValueLayout.JAVA_BYTE);
    }

    public static byte[] getKVLengthChunk(Entry<MemorySegment> entry) {
        return Integer.toHexString(entry.value().toArray(ValueLayout.JAVA_BYTE).length +
                entry.key().toArray(ValueLayout.JAVA_BYTE).length +
                "\n".length()).getBytes(StandardCharsets.UTF_8);
    }

}
