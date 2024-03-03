package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.http.Response;
import one.nio.util.ByteArrayBuilder;
import one.nio.util.Utf8;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class LSMServerResponseWithMemorySegment extends Response {
    private static final byte[] HTTP11_HEADER = Utf8.toBytes("HTTP/1.1 ");
    private static final int PROTOCOL_HEADER_LENGTH = 11;
    private final MemorySegment memorySegmentBody;

    public LSMServerResponseWithMemorySegment(String resultCode, MemorySegment body) {
        super(resultCode);
        this.memorySegmentBody = body;
        addHeader("Content-Length: " + memorySegmentBody.byteSize());
    }

    @Override
    public byte[] getBody() {
        return memorySegmentBody.toArray(ValueLayout.JAVA_BYTE);
    }

    @Override
    public void setBody(byte[] body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] toBytes(boolean includeBody) {
        int estimatedSize = PROTOCOL_HEADER_LENGTH + getHeaderCount() * 2;
        for (int i = 0; i < getHeaderCount(); i++) {
            estimatedSize += getHeaders()[i].length();
        }
        if (includeBody) {
            estimatedSize += (int) memorySegmentBody.byteSize();
        }

        ByteArrayBuilder builder = new ByteArrayBuilder(estimatedSize);
        builder.append(HTTP11_HEADER);
        for (int i = 0; i < getHeaderCount(); i++) {
            builder.append(getHeaders()[i]).append('\r').append('\n');
        }
        builder.append('\r').append('\n');
        if (includeBody) {
            writeBody(memorySegmentBody, builder);
        }
        return builder.buffer();
    }

    private static void writeBody(MemorySegment memorySegmentBody, ByteArrayBuilder builder) {
        MemorySegment.copy(
                memorySegmentBody,
                ValueLayout.JAVA_BYTE,
                0L,
                builder.buffer(),
                builder.length(),
                (int) memorySegmentBody.byteSize()
        );
    }
}
