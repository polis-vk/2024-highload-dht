package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;

public class MemoryEntryReader extends MemoryEntryFileWorker {

    public MemoryEntryReader(MemorySegment data, long offset) {
        super(data, offset);
    }

    public MemoryEntryReader(MemorySegment data) {
        super(data);
    }

    private static Entry<MemorySegment> createEntry(final MemorySegment key, final MemorySegment value) {
        return new BaseEntry<>(key, value);
    }

    private long readMeta() {
        final long result = data.get(META_LAYOUT, getOffset());
        changeOffset(META_LAYOUT.byteSize());
        return result;
    }

    private MemorySegment readMemorySegment() {
        if (!enoughForMeta()) {
            return null;
        }
        final long size = readMeta();
        if (!enoughCapacity(size)) {
            return null;
        }
        final MemorySegment result = data.asSlice(getOffset(), size);
        changeOffset(size);
        return result;
    }

    public Entry<MemorySegment> readEntry(final long limited) {

        final MemorySegment key = readMemorySegment();
        if (key == null) {
            return null;
        }
        final MemorySegment value = readMemorySegment();
        if (value == null) {
            return null;
        }
        // Без учета размеров объектов
        if (limited > 0 && limited - key.byteSize() - value.byteSize() < getOffset()) {
            return null;
        }
        return createEntry(key, value);
    }

    public Entry<MemorySegment> readEntry() {
        return readEntry(0);
    }

    public static Entry<MemorySegment> mapEntry(
            final FileChannel readerChannel,
            final long offset,
            final long keySize,
            final long valueSize,
            final Arena arena
    ) throws IOException {
        final long keyStart = offset + META_LAYOUT.byteSize();
        return createEntry(
                readerChannel.map(
                        FileChannel.MapMode.READ_ONLY,
                        keyStart,
                        keySize,
                        arena
                ),
                readerChannel.map(
                        FileChannel.MapMode.READ_ONLY,
                        keyStart + keySize + META_LAYOUT.byteSize(),
                        valueSize,
                        arena
                )
        );
    }

}
