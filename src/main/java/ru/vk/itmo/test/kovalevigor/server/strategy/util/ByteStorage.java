package ru.vk.itmo.test.kovalevigor.server.strategy.util;

public interface ByteStorage {

    long size();

    void get(int srcOffset, byte[] dst, int dstOffset, int length);

}
