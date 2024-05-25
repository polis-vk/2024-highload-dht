package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.util.ByteArrayBuilder;

public class Chunk {
    private final ByteArrayBuilder byteArrayBuilder;
    private final int offset;
    private final int length;

    public Chunk(ByteArrayBuilder buffer, int offset) {
        this.byteArrayBuilder = buffer;
        this.offset = offset;
        this.length = buffer.length() - offset;
    }

    public byte[] getBytes() {
        return byteArrayBuilder.buffer();
    }

    public int offset() {
        return offset;
    }

    public int length() {
        return length;
    }
}
