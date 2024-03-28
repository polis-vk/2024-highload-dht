package ru.vk.itmo.test.asvistukhin.dao;

import java.lang.foreign.MemorySegment;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public class SSTable {
    private final Comparator<MemorySegment> comparator = MemorySegmentUtils::compare;
    private final NavigableMap<MemorySegment, TimestampEntry<MemorySegment>> storage =
        new ConcurrentSkipListMap<>(comparator);
    private final AtomicLong sizeInBytes = new AtomicLong(0);

    public Iterator<TimestampEntry<MemorySegment>> getAll() {
        return storage.values().iterator();
    }

    public Iterator<TimestampEntry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        if (from == null && to == null) {
            return storage.values().iterator();
        }
        if (from == null) {
            return storage.headMap(to).values().iterator();
        }
        if (to == null) {
            return storage.tailMap(from).values().iterator();
        }

        return storage.subMap(from, to).values().iterator();
    }

    public TimestampEntry<MemorySegment> get(MemorySegment key) {
        return storage.get(key);
    }

    public void upsert(TimestampEntry<MemorySegment> entry) {
        TimestampEntry<MemorySegment> oldValue = storage.get(entry.key());
        storage.put(entry.key(), entry);
        if (oldValue != null) {
            sizeInBytes.addAndGet(-byteSizeOfEntry(oldValue));
        }
        sizeInBytes.addAndGet(byteSizeOfEntry(entry));
    }

    public NavigableMap<MemorySegment, TimestampEntry<MemorySegment>> getStorage() {
        return storage;
    }

    public long getStorageSize() {
        return sizeInBytes.get();
    }

    public static long byteSizeOfEntry(TimestampEntry<MemorySegment> entry) {
        long valueSize = entry.value() == null ? 0L : entry.value().byteSize();
        return entry.key().byteSize() + valueSize;
    }
}
