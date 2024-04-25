package ru.vk.itmo.test.reshetnikovaleksei;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.test.reshetnikovaleksei.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class ChunkedResponseBuilder {
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIMITER = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HTTP_OK = "HTTP/1.1 200 OK".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_TYPE = "Content-Type: text/plain".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRANSFER_ENCODING = "Transfer-Encoding: chunked".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION = "Connection: keep-alive".getBytes(StandardCharsets.UTF_8);

    private final ByteArrayBuilder builder;

    public ChunkedResponseBuilder() {
        this.builder = new ByteArrayBuilder();
    }

    @CanIgnoreReturnValue
    public ChunkedResponseBuilder withHeader() {
        builder.append(HTTP_OK, 0, HTTP_OK.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(CONTENT_TYPE, 0, CONTENT_TYPE.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(TRANSFER_ENCODING, 0, TRANSFER_ENCODING.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(CONNECTION, 0, CONNECTION.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(CRLF, 0, CRLF.length);

        return this;
    }

    @CanIgnoreReturnValue
    public ChunkedResponseBuilder withData(Iterator<Entry<MemorySegment>> iterator) {
        while (iterator.hasNext()) {
            Entry<MemorySegment> entry = iterator.next();

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
        }

        return this;
    }

    @CanIgnoreReturnValue
    public ChunkedResponseBuilder withEnd() {
        byte[] dataSize = Integer.toHexString(0).getBytes(StandardCharsets.UTF_8);
        builder.append(dataSize, 0, dataSize.length);
        builder.append(CRLF, 0, CRLF.length);
        builder.append(CRLF, 0, CRLF.length);

        return this;
    }

    public byte[] build() {
        return builder.toBytes();
    }
}
