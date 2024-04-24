package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.net.Session;
import one.nio.net.Socket;

import java.io.IOException;
import java.util.Arrays;

public class ByteQueueItem extends Session.QueueItem {
    private final byte[] bytes;

    public ByteQueueItem(byte[] bytes) {
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        // я не хотел копировать тут, меня заставил код стайл(
    }

    @Override
    public int write(Socket socket) throws IOException {
        socket.write(bytes, 0, bytes.length);
        return bytes.length;
    }
}
