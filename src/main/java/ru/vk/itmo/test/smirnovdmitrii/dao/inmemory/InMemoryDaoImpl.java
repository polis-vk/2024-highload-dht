package ru.vk.itmo.test.smirnovdmitrii.dao.inmemory;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.smirnovdmitrii.dao.state.State;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.exceptions.TooManyUpsertsException;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class InMemoryDaoImpl implements InMemoryDao<MemorySegment, Entry<MemorySegment>> {

    private static final int MAX_MEMTABLES = 2;
    private final long flushThresholdBytes;
    private final Flusher flusher;

    public InMemoryDaoImpl(
            final long flushThresholdBytes,
            final Flusher flusher
    ) {
        this.flushThresholdBytes = flushThresholdBytes;
        this.flusher = flusher;
    }

    @Override
    public List<Iterator<Entry<MemorySegment>>> get(
            final State state,
            final MemorySegment from,
            final MemorySegment to
    ) {
        final List<Iterator<Entry<MemorySegment>>> iterators = new ArrayList<>();
        for (final Memtable memtable : state.memtables()) {
            iterators.add(memtable.get(from, to));
        }
        return iterators;
    }

    @Override
    public Entry<MemorySegment> get(
            final State state,
            final MemorySegment key
    ) {
        for (final Memtable memtable : state.memtables()) {
            final Entry<MemorySegment> result = memtable.get(key);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @Override
    public boolean upsert(
            final State state,
            final Entry<MemorySegment> entry
    ) {
        final List<Memtable> currentMemtables = state.memtables();
        final Memtable memtable = currentMemtables.getFirst();
        if (memtable.upsertLock().tryLock()) {
            try {
                if (memtable.size() < flushThresholdBytes) {
                    memtable.upsert(entry);
                    return true;
                }
            } finally {
                memtable.upsertLock().unlock();
            }
            if (flusher.flush()) {
                return false;
            } else {
                if (currentMemtables.size() == MAX_MEMTABLES) {
                    throw new TooManyUpsertsException("out of memory.");
                }
            }
        }
        return false;
        // Try again. We get SSTable that will be just replaced.
    }

    @Override
    public void flush() {
        flusher.flush();
    }

    /**
     * Flushing memtable on disk.
     */
    @Override
    public void close() {
        flusher.close();
    }

}
