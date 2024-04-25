package ru.vk.itmo.test.asvistukhin;

import one.nio.http.HttpSession;
import ru.vk.itmo.test.asvistukhin.dao.TimestampEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public final class ChunkWriter {
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SEPARATOR = "\n".getBytes(StandardCharsets.UTF_8);

    private ChunkWriter() {
        // Chunk writer utils class
    }

    public static void writeAllEntries(
        HttpSession session,
        Iterator<TimestampEntry<MemorySegment>> iterator
    ) throws IOException {
        addHeaders(session);
        while (iterator.hasNext()) {
            writeChunk(session, iterator.next());
        }
        writeEmptyChunk(session);
        session.close();
    }

    private static void addHeaders(HttpSession session) throws IOException {
        writeData(session, "HTTP/1.1 200 OK".getBytes(StandardCharsets.UTF_8));
        writeData(session, CRLF);
        writeData(session, "Content-Type: text/plain".getBytes(StandardCharsets.UTF_8));
        writeData(session, CRLF);
        writeData(session, "Transfer-Encoding: chunked".getBytes(StandardCharsets.UTF_8));
        writeData(session, CRLF);
        writeData(session, "Connection: keep-alive".getBytes(StandardCharsets.UTF_8));
        writeData(session, CRLF);
        writeData(session, CRLF);
    }

    private static void writeData(HttpSession session, byte[] data) throws IOException {
        session.write(data, 0, data.length);
    }

    private static void writeChunk(HttpSession session, TimestampEntry<MemorySegment> entry) throws IOException {
        byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
        byte[] value = entry.value().toArray(ValueLayout.JAVA_BYTE);
        byte[] length = lengthToHexBytes(key.length + value.length + SEPARATOR.length);
        writeData(session, length);
        writeData(session, CRLF);
        writeData(session, key);
        writeData(session, SEPARATOR);
        writeData(session, value);
        writeData(session, CRLF);
    }

    private static void writeEmptyChunk(HttpSession session) throws IOException {
        writeData(session, lengthToHexBytes(0));
        writeData(session, CRLF);
        writeData(session, CRLF);
    }

    private static byte[] lengthToHexBytes(int length) {
        return Integer.toHexString(length).getBytes(StandardCharsets.UTF_8);
    }
}
