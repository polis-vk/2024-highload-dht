package ru.vk.itmo.test.solnyshkoksenia.dao;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.solnyshkoksenia.dao.storage.DiskStorage;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class State {
    private static final Comparator<MemorySegment> comparator = new MemorySegmentComparator();
    private static final long INDEX_SIZE = Long.BYTES;
    protected final Config config;
    protected final NavigableMap<MemorySegment, EntryExtended<MemorySegment>> storage;
    protected final NavigableMap<MemorySegment, EntryExtended<MemorySegment>> flushingStorage;
    protected final DiskStorage diskStorage;
    private final AtomicLong storageByteSize = new AtomicLong();
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private final AtomicBoolean overflow = new AtomicBoolean();

    public State(Config config,
                 NavigableMap<MemorySegment, EntryExtended<MemorySegment>> storage,
                 NavigableMap<MemorySegment, EntryExtended<MemorySegment>> flushingStorage,
                 DiskStorage diskStorage) {
        this.config = config;
        this.storage = storage;
        this.flushingStorage = flushingStorage;
        this.diskStorage = diskStorage;
    }

    public State(Config config,
                 DiskStorage diskStorage) {
        this.config = config;
        this.storage = new ConcurrentSkipListMap<>(comparator);
        this.flushingStorage = new ConcurrentSkipListMap<>(comparator);
        this.diskStorage = diskStorage;
    }

    public void putInMemory(Entry<MemorySegment> entry, Long timestamp, Long ttl) {
        MemorySegment time = longToMS(timestamp);
        MemorySegment expiration = ttl == null ? null : longToMS(timestamp + ttl);
        EntryExtended<MemorySegment> entryExtended = new EntryExtended<>(entry.key(), entry.value(), time, expiration);
        EntryExtended<MemorySegment> previousEntry = storage.put(entryExtended.key(), entryExtended);

        if (previousEntry != null) {
            storageByteSize.addAndGet(-getSize(previousEntry));
        }

        if (storageByteSize.addAndGet(getSize(entryExtended)) > config.flushThresholdBytes()) {
            overflow.set(true);
        }
    }

    public void save() throws IOException {
        diskStorage.save(storage.values());
    }

    private static long getSize(EntryExtended<MemorySegment> entry) {
        long valueSize = entry.value() == null ? 0 : entry.value().byteSize();
        long expirationSize = entry.expiration() == null ? 0 : entry.expiration().byteSize();
        return INDEX_SIZE + entry.key().byteSize() + INDEX_SIZE + valueSize + INDEX_SIZE + entry.timestamp().byteSize()
                + INDEX_SIZE + expirationSize;
    }

    @CanIgnoreReturnValue
    public State checkAndGet() {
        if (isClosed.get()) {
            throw new DaoException("Dao is already closed");
        }
        return this;
    }

    public boolean isClosed() {
        return isClosed.get();
    }

    public boolean isOverflowed() {
        return overflow.get();
    }

    public boolean isFlushing() {
        return !flushingStorage.isEmpty();
    }

    public State moveStorage() {
        return new State(config, new ConcurrentSkipListMap<>(comparator), storage, diskStorage);
    }

    public void flush() throws IOException {
        diskStorage.save(flushingStorage.values());
    }

    @CanIgnoreReturnValue
    public State close() {
        isClosed.set(true);
        return this;
    }

    public Entry<MemorySegment> get(MemorySegment key, Comparator<MemorySegment> comparator) {
        EntryExtended<MemorySegment> entry = storage.get(key);
        if (isValidEntry(entry)) {
            return entry;
        }

        entry = flushingStorage.get(key);
        if (isValidEntry(entry)) {
            return entry;
        }

        Iterator<EntryExtended<MemorySegment>> iterator = diskStorage.range(Collections.emptyIterator(), key, null);

        if (!iterator.hasNext()) {
            return null;
        }
        EntryExtended<MemorySegment> next = iterator.next();
        if (comparator.compare(next.key(), key) == 0 && isValidEntry(next)) {
            return next;
        }
        return null;
    }

    private boolean isValidEntry(EntryExtended<MemorySegment> entry) {
        return entry != null && (entry.expiration() == null
                || entry.expiration().toArray(ValueLayout.JAVA_LONG_UNALIGNED)[0] > System.currentTimeMillis());
    }

    private MemorySegment longToMS(Long value) {
        long[] ar = {value};
        return MemorySegment.ofArray(ar);
    }

    protected Iterator<EntryExtended<MemorySegment>> getInMemory(
            NavigableMap<MemorySegment, EntryExtended<MemorySegment>> memory,
            MemorySegment from, MemorySegment to) {
        if (from == null && to == null) {
            return memory.values().iterator();
        }
        if (from == null) {
            return memory.headMap(to).values().iterator();
        }
        if (to == null) {
            return memory.tailMap(from).values().iterator();
        }
        return memory.subMap(from, to).values().iterator();
    }
}
