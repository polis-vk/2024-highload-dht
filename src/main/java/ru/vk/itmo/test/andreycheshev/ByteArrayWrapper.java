package ru.vk.itmo.test.andreycheshev;


import java.nio.ByteBuffer;

public class ByteArrayWrapper {
    private final ByteBuffer array;

    public ByteArrayWrapper(byte[] array) {
        this.array = ByteBuffer.wrap(array);
    }

    public byte[] get() {
        return array.asReadOnlyBuffer().array();
    }
}
