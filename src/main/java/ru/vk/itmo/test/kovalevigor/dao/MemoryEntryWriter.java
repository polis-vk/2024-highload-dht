package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Map;

public class MemoryEntryWriter extends MemoryEntryFileWorker {

    public MemoryEntryWriter(MemorySegment data, long offset) {
        super(data, offset);
    }

    public MemoryEntryWriter(MemorySegment data) {
        super(data);
    }

    private void putMemorySegment(final MemorySegment memorySegment) {
        final long memorySegmentSize = memorySegment.byteSize();
        data.set(META_LAYOUT, getOffset(), memorySegmentSize);
        MemorySegment.copy(
                memorySegment,
                0,
                data,
                changeOffset(META_LAYOUT.byteSize()),
                memorySegmentSize
        );
        changeOffset(memorySegmentSize);
    }

    public void putEntry(final Map.Entry<MemorySegment, Entry<MemorySegment>> entry) {
        putMemorySegment(entry.getKey());
        putMemorySegment(entry.getValue().value());
    }

}
