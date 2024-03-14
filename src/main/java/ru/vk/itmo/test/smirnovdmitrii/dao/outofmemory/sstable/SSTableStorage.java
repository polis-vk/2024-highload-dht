package ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

public interface SSTableStorage extends Closeable, Iterable<SSTable> {
    /**
     * Thread save compaction method for storage. Replaces {@code compacted} SSTables with one {@code compaction}.
     * @param compactionFileName file with compacted data from SSTables.
     * @param compacted representing files that was compacted.
     */
    void compact(final String compactionFileName, final List<SSTable> compacted) throws IOException;

    /**
     * Adding SSTable to storage.
     * @param ssTableFileName SSTable to add.
     */
    void add(final String ssTableFileName) throws IOException;

    /**
     * Returns SSTable where provided SSTable was compacted. If there is no such SSTable - returns null.
     * If SSTable wasn't compacted - could return any SSTable.
     * @param ssTable compacted SSTable.
     * @return Compaction for provided SSTable.
     */
    SSTable getCompaction(final SSTable ssTable);

    @Override
    void close();
}
