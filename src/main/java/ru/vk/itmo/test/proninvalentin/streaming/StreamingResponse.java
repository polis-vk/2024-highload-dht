package ru.vk.itmo.test.proninvalentin.streaming;

import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.test.proninvalentin.dao.ExtendedEntry;
import ru.vk.itmo.test.proninvalentin.utils.MemorySegmentFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;

public class StreamingResponse extends Response {

    private static final byte[] START_STREAM_HEADERS_BYTES =
            """
                    HTTP/1.1 200 OK\r
                    Content-Type: text/plain\r
                    Transfer-Encoding: chunked\r
                    Connection: keep-alive\r
                    \r
                    """.getBytes(StandardCharsets.UTF_8);

    private static final String CRLF = "\r\n";
    private static final int PART_SIZE = 2 << 10;
    private static final byte[] FINISH_STREAM_BYTES = ("0" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_LINE_BYTES = "\n".getBytes(StandardCharsets.UTF_8);

    @Nullable
    private final Iterator<ExtendedEntry<MemorySegment>> iterator;

    public StreamingResponse(@Nullable Iterator<ExtendedEntry<MemorySegment>> iterator) {
        super(OK, EMPTY);
        this.iterator = iterator;
    }

    public void start(Socket socket) throws IOException {
        socket.write(START_STREAM_HEADERS_BYTES, 0, START_STREAM_HEADERS_BYTES.length);
    }

    public void finish(Socket socket) throws IOException {
        socket.write(FINISH_STREAM_BYTES, 0, FINISH_STREAM_BYTES.length);
    }

    public void writePart(Socket socket) throws IOException {
        byte[] part = getNextPart();
        String dataSize = Integer.toHexString(part.length);
        byte[] data = new ByteArrayBuilder(dataSize.length() + part.length + 2 * CRLF.length())
                .append(dataSize).append(CRLF)
                .append(part).append(CRLF)
                .toBytes();

        socket.write(data, 0, data.length);
    }

    private byte[] getNextPart() {
        ByteBuffer buffer = ByteBuffer.allocate(PART_SIZE);
        int offset = 0;

        while (iterator != null && remaining() > 0) {
            ExtendedEntry<MemorySegment> entry = iterator.next();
            if (entry.value() == null) {
                continue;
            }

            byte[] key = MemorySegmentFactory.toByteArray(entry.key());
            byte[] value = MemorySegmentFactory.toByteArray(entry.value());

            if (offset + key.length + value.length + NEW_LINE_BYTES.length > PART_SIZE) {
                break;
            }

            buffer.put(offset, key);
            offset += key.length;
            buffer.put(offset, NEW_LINE_BYTES);
            offset += NEW_LINE_BYTES.length;
            buffer.put(offset, value);
            offset += value.length;
        }
        return Arrays.copyOf(buffer.array(), offset);
    }

    public int remaining() {
        return iterator != null && iterator.hasNext() ? 1 : 0;
    }
}
