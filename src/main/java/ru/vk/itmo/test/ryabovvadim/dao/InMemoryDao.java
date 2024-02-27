package ru.vk.itmo.test.ryabovvadim.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ryabovvadim.dao.iterators.EntrySkipNullsIterator;
import ru.vk.itmo.test.ryabovvadim.dao.iterators.FutureIterator;
import ru.vk.itmo.test.ryabovvadim.dao.iterators.GatheringIterator;
import ru.vk.itmo.test.ryabovvadim.dao.iterators.LazyIterator;
import ru.vk.itmo.test.ryabovvadim.dao.iterators.PriorityIterator;
import ru.vk.itmo.test.ryabovvadim.dao.memtable.MemoryTable;
import ru.vk.itmo.test.ryabovvadim.dao.sstable.SSTableManager;
import ru.vk.itmo.test.ryabovvadim.dao.utils.MemorySegmentUtils;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class InMemoryDao implements Dao<MemorySegment, Entry<MemorySegment>> {
    private final MemoryTable memTable;
    private final SSTableManager ssTableManager;

    public InMemoryDao() throws IOException {
        this(null);
    }

    public InMemoryDao(Config config) throws IOException {
        this.ssTableManager = new SSTableManager(config.basePath());
        long flushThresholdBytes = config.flushThresholdBytes();
        if (flushThresholdBytes == 0) {
            flushThresholdBytes = Long.MAX_VALUE / 2;
        }
        this.memTable = new MemoryTable(ssTableManager, flushThresholdBytes);
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        Entry<MemorySegment> result = memTable.get(key);

        if (result == null && existsSSTableManager()) {
            result = ssTableManager.load(key);
        }
        return handleDeletededEntry(result);
    }

    @Override
    public Iterator<Entry<MemorySegment>> allFrom(MemorySegment from) {
        return get(from, null);
    }

    @Override
    public Iterator<Entry<MemorySegment>> allTo(MemorySegment to) {
        return get(null, to);
    }

    @Override
    public Iterator<Entry<MemorySegment>> all() {
        return get(null, null);
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return makeIteratorWithSkipNulls(from, to);
    }

    private Entry<MemorySegment> handleDeletededEntry(Entry<MemorySegment> entry) {
        if (entry == null || entry.value() == null) {
            return null;
        }
        return entry;
    }

    private FutureIterator<Entry<MemorySegment>> makeIteratorWithSkipNulls(
            MemorySegment from,
            MemorySegment to
    ) {
        Iterator<Entry<MemorySegment>> memoryIterator = memTable.get(from, to);
        if (!existsSSTableManager() || ssTableManager.size() == 0) {
            return new EntrySkipNullsIterator(memoryIterator);
        }

        int priority = 0;
        List<FutureIterator<Entry<MemorySegment>>> loadedIterators = ssTableManager.load(from, to);
        List<PriorityIterator<Entry<MemorySegment>>> priorityIterators = new ArrayList<>();

        for (FutureIterator<Entry<MemorySegment>> it : loadedIterators) {
            priorityIterators.add(new PriorityIterator<>(it, priority++));
        }
        if (memoryIterator.hasNext()) {
            priorityIterators.add(new PriorityIterator<>(new LazyIterator<>(memoryIterator), priority));
        }

        GatheringIterator<Entry<MemorySegment>> gatheringIterator = new GatheringIterator<>(
                priorityIterators,
                Comparator.comparing(
                        (PriorityIterator<Entry<MemorySegment>> it) -> it.showNext().key(),
                        MemorySegmentUtils::compareMemorySegments
                ).thenComparing(Comparator.comparingInt((PriorityIterator<?> it) -> it.getPriority()).reversed()),
                Comparator.comparing(Entry::key, MemorySegmentUtils::compareMemorySegments)
        );

        return new EntrySkipNullsIterator(gatheringIterator);
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        memTable.upsert(entry);
    }

    @Override
    public void close() throws IOException {
        memTable.close();
        ssTableManager.close();
    }

    @Override
    public void flush() throws IOException {
        memTable.flush(false);
    }

    @Override
    public void compact() throws IOException {
        if (!existsSSTableManager()) {
            return;
        }

        ssTableManager.compact();
    }

    private boolean existsSSTableManager() {
        return ssTableManager != null;
    }
}
