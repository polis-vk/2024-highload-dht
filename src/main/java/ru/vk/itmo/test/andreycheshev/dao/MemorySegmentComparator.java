package ru.vk.itmo.test.andreycheshev.dao;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Comparator;

/**
 * Compares {@link MemorySegment}s.
 *
 * @author incubos
 */
final class MemorySegmentComparator implements Comparator<MemorySegment> {
    static final Comparator<MemorySegment> INSTANCE =
            new MemorySegmentComparator();

    private MemorySegmentComparator() {
        // Singleton
    }

    @Override
    public int compare(
            final MemorySegment left,
            final MemorySegment right) {
        final long mismatch = left.mismatch(right);
        if (mismatch == -1L) {
            // No mismatch
            return 0;
        }

        if (mismatch == left.byteSize()) {
            // left is prefix of right, so left is smaller
            return -1;
        }

        if (mismatch == right.byteSize()) {
            // right is prefix of left, so left is greater
            return 1;
        }

        // Compare mismatched bytes as unsigned
        return Byte.compareUnsigned(
                left.getAtIndex(
                        ValueLayout.OfByte.JAVA_BYTE,
                        mismatch),
                right.getAtIndex(
                        ValueLayout.OfByte.JAVA_BYTE,
                        mismatch));
    }

    static int compare(
            final MemorySegment srcSegment,
            final long srcFromOffset,
            final long srcLength,
            final MemorySegment dstSegment,
            final long dstFromOffset,
            final long dstLength) {
        final long mismatch =
                MemorySegment.mismatch(
                        srcSegment,
                        srcFromOffset,
                        srcFromOffset + srcLength,
                        dstSegment,
                        dstFromOffset,
                        dstFromOffset + dstLength);
        if (mismatch == -1L) {
            // No mismatch
            return 0;
        }

        if (mismatch == srcLength) {
            // left is prefix of right, so left is smaller
            return -1;
        }

        if (mismatch == dstLength) {
            // right is prefix of left, so left is greater
            return 1;
        }

        // Compare mismatched bytes as unsigned
        return Byte.compareUnsigned(
                srcSegment.getAtIndex(
                        ValueLayout.OfByte.JAVA_BYTE,
                        srcFromOffset + mismatch),
                dstSegment.getAtIndex(
                        ValueLayout.OfByte.JAVA_BYTE,
                        dstFromOffset + mismatch));
    }
}
