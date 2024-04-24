package ru.vk.itmo.test.kovalevigor.server.strategy.util;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class MemorySegmentByteStorage implements ByteStorage {

    private final MemorySegment data;

    public MemorySegmentByteStorage(MemorySegment data) {
        this.data = data;
    }

    @Override
    public long size() {
        return data == null ? 0 : data.byteSize();
    }

    @Override
    public void get(int srcOffset, byte[] dst, int dstOffset, int length) {
        if (data == null) {
            return;
        }
        for (int i = 0; i < length; i++) {
            dst[dstOffset + i] = data.get(ValueLayout.JAVA_BYTE, srcOffset + i);
        }
    }
}
