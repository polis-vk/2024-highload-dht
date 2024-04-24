package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.net.Session;
import one.nio.net.Socket;

import java.io.IOException;

public class ByteQueueItem extends Session.QueueItem {
    private byte[] bytes;
    public ByteQueueItem(byte[] bytes) {
        this.bytes = bytes;
    }


    @Override
    public int write(Socket socket) throws IOException {
        socket.write(bytes, 0, bytes.length);
        return bytes.length;
    }
}
