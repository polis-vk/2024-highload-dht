package ru.vk.itmo.test.andreycheshev;

import one.nio.net.Session;

import java.io.IOException;

public class StreamingBuffer {
    private final byte[] buffer;
    private final Session session;
    private boolean isReadyToWrite = false;
    private int size;

    public StreamingBuffer(int buffSize, Session session) {
        this.buffer = new byte[buffSize];
        this.session = session;
    }

    public void copyIn(byte[] bytes) {
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
    }

    public void write() throws IOException {
        session.write(buffer, 0, size);
    }

    public void writeIfReady() throws IOException {
        if (isReadyToWrite) {
            session.write(buffer, 0, size);
            isReadyToWrite = false;
        }
    }

    public void setReadyToWrite() {
        isReadyToWrite = true;
    }

    public int size() {
        return size;
    }

    public void reset() {
        size = 0;
    }
}
