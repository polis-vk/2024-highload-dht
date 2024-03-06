package ru.vk.itmo.test.ryabovvadim.dao.utils;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public final class MemorySegmentUtils {

    public static int compareMemorySegments(MemorySegment left, MemorySegment right) {
        return compareMemorySegments(
                left, 0, left == null ? 0 : left.byteSize(),
                right, 0, right == null ? 0 : right.byteSize()
        );
    }

    public static int compareMemorySegments(
            MemorySegment left,
            long leftFromOffset,
            long leftToOffset,
            MemorySegment right,
            long rightFromOffset,
            long rightToOffset
    ) {
        if (left == null) {
            return right == null ? 0 : -1;
        } else if (right == null) {
            return 1;
        }

        long leftSize = leftToOffset - leftFromOffset;
        long rightSize = rightToOffset - rightFromOffset;
        long mismatch = MemorySegment.mismatch(
                left, leftFromOffset, leftToOffset,
                right, rightFromOffset, rightToOffset
        );

        if (mismatch == leftSize) {
            return -1;
        }
        if (mismatch == rightSize) {
            return 1;
        }
        if (mismatch == -1) {
            return 0;
        }

        return Byte.compareUnsigned(
                left.get(JAVA_BYTE, leftFromOffset + mismatch),
                right.get(JAVA_BYTE, rightFromOffset + mismatch)
        );
    }

    public static void copyByteArray(byte[] src, MemorySegment dst, long offsetDst) {
        MemorySegment.copy(
                src,
                0,
                dst,
                JAVA_BYTE,
                offsetDst,
                src.length
        );
    }

    private MemorySegmentUtils() {
    }
}
