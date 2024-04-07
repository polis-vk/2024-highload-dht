package ru.vk.itmo.test.tyapuevdmitrij.dao;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Comparator;

public final class MemorySegmentComparator {
    private MemorySegmentComparator() {
        throw new IllegalStateException("Utility class");
    }

     static Comparator<MemorySegment> getMemorySegmentComparator() {
        return (segment1, segment2) -> {
            long offset = segment1.mismatch(segment2);
            if (offset == -1) {
                return 0;
            }
            if (offset == segment1.byteSize()) {
                return -1;
            }
            if (offset == segment2.byteSize()) {
                return 1;
            }
            return segment1.get(ValueLayout.JAVA_BYTE, offset) - segment2.get(ValueLayout.JAVA_BYTE, offset);
        };
    }
}

