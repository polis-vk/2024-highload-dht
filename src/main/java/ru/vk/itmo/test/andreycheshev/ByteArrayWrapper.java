package ru.vk.itmo.test.andreycheshev;

import java.util.Arrays;

public class ByteArrayWrapper {
    private final byte[] array;

    public ByteArrayWrapper(byte[] array) {
        this.array = Arrays.copyOf(array, array.length);
    }

    public byte[] get() {
        return array;
    }
}