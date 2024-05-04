package ru.vk.itmo.test.andreycheshev;

import one.nio.net.Socket;

import java.io.IOException;

public class StreamingBuffer {
    private final byte[] buffer;
    private int size;
    private int pos;

    public StreamingBuffer(int buffSize) {
        this.buffer = new byte[buffSize];
    }

    public void append(byte[] bytes) {
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size - pos <= 0;
    }

    public void reset() {
        size = 0;
        pos = 0;
    }

    public int tryWrite(Socket socket) throws IOException {
        int diff = size - pos;
        if (diff > 0) {
            int written = socket.write(buffer, pos, diff);
            if (diff == written) {
                reset();
            } else {
                pos += written;
            }
            return written;
        }
        return 0;
    }

    public boolean isFits(int length) {
        return size + length <= buffer.length;
    }
}
