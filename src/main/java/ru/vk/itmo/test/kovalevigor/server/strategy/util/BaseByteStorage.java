package ru.vk.itmo.test.kovalevigor.server.strategy.util;

import java.util.Arrays;

public class BaseByteStorage implements ByteStorage {

    private final byte[] data;

    public BaseByteStorage(byte[] data) {
        this.data = Arrays.copyOf(data, data.length);
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
