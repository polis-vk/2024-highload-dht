package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.AbstractList;
import java.util.RandomAccess;

public class IndexList extends AbstractList<Entry<MemorySegment>> implements RandomAccess {

    public static final long INDEX_ENTRY_SIZE = IndexDumper.ENTRY_SIZE;
    public static final long META_INFO_SIZE = IndexDumper.HEAD_SIZE;
    public static long MAX_BYTE_SIZE = META_INFO_SIZE + Integer.MAX_VALUE * INDEX_ENTRY_SIZE;

    private final MemorySegment indexSegment;
    private final MemorySegment dataSegment;
    private final long keysSize;
    private final long valuesSize;

    public IndexList(final MemorySegment indexSegment, final MemorySegment dataSegment) {
        if (indexSegment.byteSize() > MAX_BYTE_SIZE) {
            this.indexSegment = indexSegment.asSlice(0, MAX_BYTE_SIZE);
        } else {
            this.indexSegment = indexSegment;
        }

        this.dataSegment = dataSegment;
        this.keysSize = readOffset(0);
        this.valuesSize = readOffset(ValueLayout.JAVA_LONG.byteSize());
    }

    private long getEntryOffset(final int index) {
        if (size() <= index) {
            return -1;
        }
        return META_INFO_SIZE + INDEX_ENTRY_SIZE * index;
    }

    private long readOffset(final long offset) {
        return indexSegment.get(ValueLayout.JAVA_LONG, offset);
    }

    public class LazyEntry implements Entry<MemorySegment> {

        private final MemorySegment key;
        private final int index;
        private MemorySegment value;

        private LazyEntry(MemorySegment key, int index) {
            this.key = key;
            this.index = index;
        }

        @Override
        public MemorySegment key() {
            return key;
        }

        @Override
        public MemorySegment value() {
            if (value == null) {
                value = getValue(index);
            }
            return value;
        }

    }

    private MemorySegment getValue(final int index) {
        final long offset = getEntryOffset(index);
        final long valueOffset = readOffset(offset + ValueLayout.JAVA_LONG.byteSize());
        final long nextEntryOffset = getEntryOffset(index + 1);

        final MemorySegment value;
        if (valueOffset >= 0) {

            long valueSize;
            if (nextEntryOffset == -1) {
                valueSize = dataSegment.byteSize() - keysSize();
            } else {
                final long nextValueOffset = readOffset(nextEntryOffset + ValueLayout.JAVA_LONG.byteSize());
                valueSize = (nextValueOffset > 0 ? nextValueOffset : -(nextValueOffset + 1));
            }
            value = dataSegment.asSlice(valueOffset + keysSize(), valueSize - valueOffset);
        } else {
            value = null;
        }
        return value;
    }

    @Override
    public LazyEntry get(final int index) {
        final long offset = getEntryOffset(index);

        final long keyOffset = readOffset(offset);
        final long nextEntryOffset = getEntryOffset(index + 1);

        long keySize = (nextEntryOffset == -1 ? keysSize : readOffset(nextEntryOffset)) - keyOffset;

        final MemorySegment key = dataSegment.asSlice(keyOffset, keySize);
        return new LazyEntry(key, index);
    }

    @Override
    public int size() {
        return (int)((indexSegment.byteSize() - META_INFO_SIZE) / INDEX_ENTRY_SIZE);
    }

    public long keysSize() {
        return keysSize;
    }

    public long valuesSize() {
        return valuesSize;
    }

}
