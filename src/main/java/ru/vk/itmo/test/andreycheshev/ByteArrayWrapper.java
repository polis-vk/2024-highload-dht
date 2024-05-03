package ru.vk.itmo.test.andreycheshev;

public class ByteArrayWrapper {
    private final byte[] array;

    public ByteArrayWrapper(byte[] array) {
        this.array = array;
    }

    public byte[] get() {
        return array;
    }
}
