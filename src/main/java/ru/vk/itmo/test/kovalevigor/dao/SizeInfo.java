package ru.vk.itmo.test.kovalevigor.dao;

public class SizeInfo {

    public long size;
    public long keysSize;
    public long valuesSize;

    public SizeInfo(final long size, final long keysSize, final long valuesSize) {
        this.size = size;
        this.keysSize = keysSize;
        this.valuesSize = valuesSize;
    }

    public SizeInfo(final long size, final long keysSize) {
        this(size, keysSize, 0);
    }

    public SizeInfo(final long size) {
        this(size, 0);
    }

    public SizeInfo() {
        this(0);
    }

    public void add(final SizeInfo sizeInfo) {
        size += sizeInfo.size;
        keysSize += sizeInfo.keysSize;
        valuesSize += sizeInfo.valuesSize;
    }
}
