package ru.vk.itmo.test.viktorkorotkikh.dao.io.read;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.viktorkorotkikh.dao.LSMPointerIterator;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Base class for implementing the sstable reader.
 *
 * @author vitekkor
 * @see BaseSSTableReader
 */
public abstract class AbstractSSTableReader {
    protected final MemorySegment mappedSSTable;
    protected final MemorySegment mappedIndexFile;
    protected final int index;

    protected AbstractSSTableReader(
            MemorySegment mappedSSTable,
            MemorySegment mappedIndexFile,
            int index
    ) {
        this.mappedSSTable = mappedSSTable;
        this.mappedIndexFile = mappedIndexFile;
        this.index = index;
    }

    /**
     * Returns entry by key.
     * @param key entry`s key
     * @return entry
     * @throws IOException if an I/O error occurs.
     */
    public TimestampedEntry<MemorySegment> get(MemorySegment key) throws IOException {
        try {
            long entryOffset = getEntryOffset(key, SearchOption.EQ);
            if (entryOffset == -1) {
                return null;
            }
            return getByIndex(entryOffset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns ordered iterator of entries with keys between from (inclusive) and to (exclusive).
     * @param from lower bound of range (inclusive)
     * @param to upper bound of range (exclusive)
     * @return entries [from;to)
     * @throws IOException if an I/O error occurs.
     */
    public abstract LSMPointerIterator iterator(MemorySegment from, MemorySegment to) throws IOException;

    /**
     * Returns entry by index.
     * @param index entry`s index from index file.
     * @return entry
     * @throws IOException if an I/O error occurs.
     */
    protected abstract TimestampedEntry<MemorySegment> getByIndex(long index) throws IOException;

    /**
     * Returns entry's index by key.
     * @param key entry`s key
     * @param searchOption Entity search parameter. {@link SearchOption#EQ} find equal by key,
     *                     {@link SearchOption#GTE} find greater than or equal to,
     *                     {@link SearchOption#LT} strictly less than
     * @return offset (index)
     * @throws IOException if an I/O error occurs.
     */
    protected abstract long getEntryOffset(MemorySegment key, SearchOption searchOption) throws IOException;

    /**
     * SSTable has no tombstones.
     * @return false if sstable has tombstones.
     */
    public boolean hasNoTombstones() {
        return mappedIndexFile.get(ValueLayout.JAVA_BOOLEAN, 0);
    }

    public enum SearchOption {
        EQ, GTE, LT
    }
}
