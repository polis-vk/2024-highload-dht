package ru.vk.itmo.test.kovalevigor.dao.entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class MSegmentTimeEntry extends BaseDaoEntry<MemorySegment> implements TimeEntry<MemorySegment> {
    private final Long timestamp;
    private static final ValueLayout TIMESTAMP_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED;

    public MSegmentTimeEntry(MemorySegment key, MemorySegment value, Long timestamp) {
        super(key, value);
        this.timestamp = timestamp;
    }

    @Override
    public Long timestamp() {
        return timestamp;
    }

    @Override
    public long valueSize() {
        return TIMESTAMP_LAYOUT.byteSize() + value().byteSize();
    }
}
