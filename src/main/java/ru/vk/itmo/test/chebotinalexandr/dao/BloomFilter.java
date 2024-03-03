package ru.vk.itmo.test.chebotinalexandr.dao;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static ru.vk.itmo.test.chebotinalexandr.dao.SSTableUtils.BLOOM_FILTER_HASH_FUNCTIONS_OFFSET;
import static ru.vk.itmo.test.chebotinalexandr.dao.SSTableUtils.BLOOM_FILTER_LENGTH_OFFSET;

public final class BloomFilter {
    private static final int LONG_ADDRESSABLE_BITS = 6;

    private BloomFilter() {

    }

    public static long bloomFilterLength(long entriesCount, double falsePositiveRate) {
        long n = divide(entriesCount);
        return (long) (-n * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
    }

    /** Divides {@code entriesCount} by {@code 64} with ceiling rounding.
     */
    private static long divide(long entriesCount) {
        long div = entriesCount / (long) Long.SIZE;

        //no rounding required
        if (entriesCount % (long) Long.SIZE == 0) {
            return div;
        }

        return div + 1;
    }

    public static void addToSstable(MemorySegment key, MemorySegment sstable, int hashFunctionsNum, long bitSize) {
        long[] indexes = MurmurHash.hash64(key, 0, (int) key.byteSize());

        long base = indexes[0];
        long inc = indexes[1];

        long combinedHash = base;
        for (int i = 0; i < hashFunctionsNum; i++) {
            long bitIndex = (combinedHash & Long.MAX_VALUE) % bitSize;
            set(bitIndex, sstable);
            combinedHash += inc;
        }
    }

    private static void set(long bitIndex, MemorySegment sstable) {
        long bitOffset = offsetForIndex(bitIndex);

        long mask = 1L << bitIndex;

        long oldValue = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED, bitOffset);
        sstable.set(ValueLayout.JAVA_LONG_UNALIGNED, bitOffset, oldValue | mask);
    }

    public static boolean sstableMayContain(MemorySegment key, MemorySegment sstable) {
        long[] indexes = MurmurHash.hash64(key, 0, (int) key.byteSize(), MurmurHash.DEFAULT_SEED);

        long base = indexes[0];
        long inc = indexes[1];

        long bitSize = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED, BLOOM_FILTER_LENGTH_OFFSET) * Long.SIZE;
        long hashFunctions = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED, BLOOM_FILTER_HASH_FUNCTIONS_OFFSET);

        long combinedHash = base;
        for (int i = 0; i < hashFunctions; i++) {
            if (!getFromSstable((combinedHash & Long.MAX_VALUE) % bitSize, sstable)) {
                return false;
            }
            combinedHash += inc;
        }

        return true;
    }

    private static boolean getFromSstable(long bitIndex, MemorySegment sstable) {
        long bitOffset = offsetForIndex(bitIndex);

        long hashFromSstable = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED, bitOffset);
        return (hashFromSstable & (1L << bitIndex)) != 0;
    }

    /** Get sstable offset for bloom filter array index.
     */
    private static long offsetForIndex(long bitIndex) {
        long longIndex = bitIndex >>> LONG_ADDRESSABLE_BITS;
        return 3L * Long.BYTES + longIndex * Long.BYTES;
    }

}
