package ru.vk.itmo.test.andreycheshev;

import java.nio.ByteBuffer;

public class ByteArrayWrapper {
    private final ByteBuffer buffer;

    public ByteArrayWrapper(byte[] data) {
        this.buffer = ByteBuffer.wrap(data).asReadOnlyBuffer();
    }

    public byte[] get() {
        byte[] copy = new byte[buffer.remaining()];
        buffer.slice().get(copy);
        return copy;
    }
}
