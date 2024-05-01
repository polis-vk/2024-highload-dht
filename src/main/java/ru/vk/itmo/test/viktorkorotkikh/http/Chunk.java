package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.util.ByteArrayBuilder;

public class Chunk {
    private final byte[] bytes;
    private final int offset;
    private final int length;

    public Chunk(ByteArrayBuilder buffer, int offset) {
        this.bytes = buffer.buffer();
        this.offset = offset;
        this.length = buffer.length();
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int offset() {
        return offset;
    }

    public int length() {
        return length;
    }
}
