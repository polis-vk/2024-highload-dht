package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.net.Session;
import one.nio.net.Socket;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class HttpChunkedEntry extends Session.QueueItem {
    private static final byte[] SEPARATOR = "\n".getBytes(StandardCharsets.UTF_8);
    Entry<MemorySegment> entry;

    public HttpChunkedEntry(Entry<MemorySegment> entry) {
        this.entry = entry;
    }

    @Override
    public int write(Socket socket) throws IOException {
        byte[] key = getBytes(entry.key());
        byte[] value = getBytes(entry.value());
        byte[] length = Long.toHexString(key.length + value.length + SEPARATOR.length)
                .getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        byte[] output = new byte[length.length + key.length + value.length + 3 * SEPARATOR.length];
        offset += copy(length, output, offset);
        offset += copy(SEPARATOR, output, offset);
        offset += copy(key, output, offset);
        offset += copy(SEPARATOR, output, offset);
        offset += copy(value, output, offset);
        offset += copy(SEPARATOR, output, offset);
        socket.write(output, 0, output.length);
        return offset;
    }

    private static int copy(byte[] from, byte[] to, int destOffset) {
        System.arraycopy(from, 0, to, destOffset, from.length);
        return from.length;
    }

    private static byte[] getBytes(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }
}
