package ru.vk.itmo.test.emelyanovvitaliy;

import java.nio.charset.StandardCharsets;

public class HttpChunkedHeaderQueueItem extends ByteQueueItem {
    private static final byte[] HTTP_CHUNKED_HEADER = """
            HTTP/1.1 200 OK
            Content-Type: text/plain
            Transfer-Encoding: chunked
            Connection: close 

            """.getBytes(StandardCharsets.UTF_8);

    public HttpChunkedHeaderQueueItem() {
        super(HTTP_CHUNKED_HEADER);
    }
}

