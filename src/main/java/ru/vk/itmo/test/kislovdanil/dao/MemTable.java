package ru.vk.itmo.test.kislovdanil.dao;

import java.lang.foreign.MemorySegment;
import java.util.Comparator;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/* Basically, ConcurrentSkipList with bytes threshold.
 */
public class MemTable {
    private final ConcurrentSkipListMap<MemorySegment, Entry<MemorySegment>> storage;
    private final long threshold;
    private final AtomicLong size = new AtomicLong(0);

    private static long getEntrySize(Entry<MemorySegment> entry) {
        long valueSize = entry.value() == null ? 0 : entry.value().byteSize();
        return valueSize + entry.key().byteSize();
    }

    public MemTable(Comparator<MemorySegment> comparator, long threshold) {
        this.storage = new ConcurrentSkipListMap<>(comparator);
        this.threshold = threshold;
    }

    public boolean put(Entry<MemorySegment> entry) {
        long entrySize = getEntrySize(entry);
        if (size.addAndGet(entrySize) - entrySize > threshold) {
            return false;
        }
        storage.put(entry.key(), entry);
        return true;
    }

    public ConcurrentSkipListMap<MemorySegment, Entry<MemorySegment>> getStorage() {
        return storage;
    }
}
