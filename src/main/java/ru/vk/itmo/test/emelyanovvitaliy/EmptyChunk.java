package ru.vk.itmo.test.emelyanovvitaliy;

import java.nio.charset.StandardCharsets;

public class EmptyChunk extends ByteQueueItem {
    private static final byte[] HTTP_CHUNKED_HEADER = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    public EmptyChunk() {
        super(HTTP_CHUNKED_HEADER);
    }
}

