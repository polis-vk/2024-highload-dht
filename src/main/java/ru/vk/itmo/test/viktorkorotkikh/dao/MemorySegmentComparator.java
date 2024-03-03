package ru.vk.itmo.test.viktorkorotkikh.dao;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Comparator;

public class MemorySegmentComparator implements Comparator<MemorySegment> {

    public static final MemorySegmentComparator INSTANCE = new MemorySegmentComparator();

    @Override
    public int compare(MemorySegment o1, MemorySegment o2) {
        // range of 0 (inclusive) up to the size (in bytes) of the smaller memory segment (exclusive).
        long mismatch = o1.mismatch(o2);
        if (mismatch == -1) { // equals
            return 0;
        }

        if (mismatch == o1.byteSize()) { // o1 is smaller memory segment
            return -1;
        }

        if (mismatch == o2.byteSize()) { // o2 is smaller memory segment
            return 1;
        }

        return o1.get(ValueLayout.JAVA_BYTE, mismatch) - o2.get(ValueLayout.JAVA_BYTE, mismatch);
    }

    public int compare(
            MemorySegment srcSegment,
            long srcFromOffset,
            long srcToOffset,
            MemorySegment dstSegment,
            long dstFromOffset,
            long dstToOffset
    ) {
        long mismatch = MemorySegment
                .mismatch(srcSegment, srcFromOffset, srcToOffset, dstSegment, dstFromOffset, dstToOffset);
        if (mismatch == -1) { // equals
            return 0;
        }

        if (mismatch == srcToOffset - srcFromOffset) { // keyFromSrcMemorySegment is smaller memory segment
            return -1;
        }

        if (mismatch == dstSegment.byteSize()) { // keyFromDstMemorySegment is smaller memory segment
            return 1;
        }
        byte o1 = srcSegment.get(ValueLayout.JAVA_BYTE, srcFromOffset + mismatch);
        byte o2 = dstSegment.get(ValueLayout.JAVA_BYTE, dstFromOffset + mismatch);
        return o1 - o2;
    }

    public int compare(
            MemorySegment srcSegment,
            long srcFromOffset,
            long srcToOffset,
            MemorySegment dstSegment
    ) {
        return compare(srcSegment, srcFromOffset, srcToOffset, dstSegment, 0, dstSegment.byteSize());
    }
}
