package ru.vk.itmo.test.reshetnikovaleksei;

import one.nio.http.HttpSession;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.test.reshetnikovaleksei.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class ChunkedResponseSender {
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIMITER = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HTTP_OK = "HTTP/1.1 200 OK".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_TYPE = "Content-Type: text/plain".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRANSFER_ENCODING = "Transfer-Encoding: chunked".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION = "Connection: keep-alive".getBytes(StandardCharsets.UTF_8);

    private ChunkedResponseSender() { }

    public static void generateHeaderAndWrite(HttpSession session) throws IOException {
        ByteArrayBuilder builder = new ByteArrayBuilder();

        builder.append(HTTP_OK, 0, HTTP_OK.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(CONTENT_TYPE, 0, CONTENT_TYPE.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(TRANSFER_ENCODING, 0, TRANSFER_ENCODING.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(CONNECTION, 0, CONNECTION.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(CRLF, 0, CRLF.length);

        write(builder, session);
    }

    public static void generateDataChunkAndWrite(HttpSession session, Entry<MemorySegment> entry) throws IOException {
        ByteArrayBuilder builder = new ByteArrayBuilder();

        byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
        byte[] value = entry.value().toArray(ValueLayout.JAVA_BYTE);
        byte[] length = Integer.toHexString(key.length + value.length + DELIMITER.length)
                .getBytes(StandardCharsets.UTF_8);
        builder.append(length, 0, length.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(key, 0, key.length);
        builder.append(DELIMITER, 0, DELIMITER.length);
        builder.append(value, 0, value.length);
        builder.append(CRLF, 0, CRLF.length);

        write(builder, session);
    }

    public static void generateEndChunkAndWrite(HttpSession session) throws IOException {
        ByteArrayBuilder builder = new ByteArrayBuilder();

        byte[] dataSize = Integer.toHexString(0).getBytes(StandardCharsets.UTF_8);
        builder.append(dataSize, 0, dataSize.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(CRLF, 0, CRLF.length);

        write(builder, session);
    }

    private static void write(ByteArrayBuilder builder, HttpSession session) throws IOException {
        byte[] bytesToWrite = builder.toBytes();
        session.write(bytesToWrite, 0, bytesToWrite.length);
    }
}
