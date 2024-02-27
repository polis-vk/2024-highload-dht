package ru.vk.itmo.test.chebotinalexandr.dao;

public class SSTableOffsets {
    private final long bloomFilterHashFunctionsOffset;
    private final long bloomFilterLengthOffset;
    private final long entriesSizeOffset;

    public SSTableOffsets(long bloomFilterHashFunctionsOffset, long bloomFilterLengthOffset, long entriesSizeOffset) {
        this.bloomFilterHashFunctionsOffset = bloomFilterHashFunctionsOffset;
        this.bloomFilterLengthOffset = bloomFilterLengthOffset;
        this.entriesSizeOffset = entriesSizeOffset;
    }

    public long getBloomFilterHashFunctionsOffset() {
        return bloomFilterHashFunctionsOffset;
    }

    public long getBloomFilterLengthOffset() {
        return bloomFilterLengthOffset;
    }

    public long getEntriesSizeOffset() {
        return entriesSizeOffset;
    }
}
