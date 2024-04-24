package ru.vk.itmo.test.kovalevigor.server.strategy.util;

public class BaseByteStorage implements ByteStorage {

    private final byte[] data;

    public BaseByteStorage(byte[] data) {
        this.data = data;
    }

    @Override
    public long size() {
        return data.length;
    }

    @Override
    public void get(int srcOffset, byte[] dst, int dstOffset, int length) {
        System.arraycopy(data, srcOffset, dst, dstOffset, length);
    }
}
