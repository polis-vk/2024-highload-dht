package ru.vk.itmo.test.klimplyasov;

import one.nio.http.HttpSession;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class ChunkGenerator {
    private static final byte[] HTTP_RESPONSE_LINE = "HTTP/1.1 200 OK".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_TYPE_HEADER = "Content-Type: text/plain".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRANSFER_ENCODING_HEADER = "Transfer-Encoding: chunked".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_HEADER = "Connection: keep-alive".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LF = "\n".getBytes(StandardCharsets.UTF_8);

    public static void writeResponseHeaders(HttpSession session) throws IOException {
        session.write(HTTP_RESPONSE_LINE, 0, HTTP_RESPONSE_LINE.length);
        session.write(CRLF, 0, CRLF.length);
        session.write(CONTENT_TYPE_HEADER, 0, CONTENT_TYPE_HEADER.length);
        session.write(CRLF, 0, CRLF.length);
        session.write(TRANSFER_ENCODING_HEADER, 0, TRANSFER_ENCODING_HEADER.length);
        session.write(CRLF, 0, CRLF.length);
        session.write(CONNECTION_HEADER, 0, CONNECTION_HEADER.length);
        session.write(CRLF, 0, CRLF.length);
        session.write(CRLF, 0, CRLF.length);
    }

    public static void writeDataChunk(HttpSession session, Entry<MemorySegment> entry) throws IOException {
        byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
        byte[] value = entry.value().toArray(ValueLayout.JAVA_BYTE);
        byte[] lengthBytes = Integer.toHexString(key.length + value.length + LF.length).getBytes(StandardCharsets.UTF_8);
        session.write(lengthBytes, 0, lengthBytes.length);
        session.write(CRLF, 0, CRLF.length);
        session.write(key, 0, key.length);
        session.write(LF, 0, LF.length);
        session.write(value, 0, value.length);
        session.write(CRLF, 0, CRLF.length);
    }

    public static void writeEmptyChunk(HttpSession session) throws IOException {
        byte[] dataSizeBytes = Integer.toHexString(0).getBytes(StandardCharsets.UTF_8);
        session.write(dataSizeBytes, 0, dataSizeBytes.length);
        session.write(CRLF, 0, CRLF.length);
        session.write(CRLF, 0, CRLF.length);
    }
}
