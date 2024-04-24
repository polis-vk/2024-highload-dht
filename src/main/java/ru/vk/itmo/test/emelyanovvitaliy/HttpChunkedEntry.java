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
        String length = Long.toHexString(key.length + value.length + SEPARATOR.length);
        int written = 0;
        written += writeWithBreak(socket, length.getBytes(StandardCharsets.UTF_8));
        written += writeWithBreak(socket, key);
        written += writeWithBreak(socket, value);
        return written;
    }

    private static int br(Socket socket) throws IOException {
        socket.write(SEPARATOR, 0, SEPARATOR.length);
        return SEPARATOR.length;
    }

    private static int write(Socket socket, byte[] bytes) throws IOException {
        socket.write(bytes, 0, bytes.length);
        return bytes.length;
    }

    private static int writeWithBreak(Socket socket, byte[] bytes) throws IOException {
        return write(socket, bytes) + br(socket);
    }

    private static byte[] getBytes(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }
}
