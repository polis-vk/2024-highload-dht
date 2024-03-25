package ru.vk.itmo.test.smirnovdmitrii.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.smirnovdmitrii.dao.inmemory.Flusher;
import ru.vk.itmo.test.smirnovdmitrii.dao.inmemory.InMemoryDao;
import ru.vk.itmo.test.smirnovdmitrii.dao.inmemory.InMemoryDaoImpl;
import ru.vk.itmo.test.smirnovdmitrii.dao.inmemory.SkipListMemtable;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.FileDao;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.OutMemoryDao;
import ru.vk.itmo.test.smirnovdmitrii.dao.state.State;
import ru.vk.itmo.test.smirnovdmitrii.dao.state.StateService;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.EqualsComparator;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.MemorySegmentComparator;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.iterators.MergeIterator;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.iterators.WrappedIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class DaoImpl implements Dao<MemorySegment, TimeEntry<MemorySegment>> {
    private final InMemoryDao<MemorySegment, TimeEntry<MemorySegment>> inMemoryDao;
    private final OutMemoryDao<MemorySegment, TimeEntry<MemorySegment>> outMemoryDao;
    private final StateService stateService = new StateService();
    private final EqualsComparator<MemorySegment> comparator = new MemorySegmentComparator();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    public DaoImpl(final Config config) {
        outMemoryDao = new FileDao(config.basePath(), stateService);
        final Flusher flusher = new Flusher(stateService, outMemoryDao, SkipListMemtable::new);
        inMemoryDao = new InMemoryDaoImpl(config.flushThresholdBytes(), flusher);
    }

    @Override
    public Iterator<TimeEntry<MemorySegment>> get(final MemorySegment from, final MemorySegment to) {
        int id = 0;
        final MergeIterator.Builder<MemorySegment, TimeEntry<MemorySegment>> builder
                = new MergeIterator.Builder<>(comparator);
        final State state = stateService.state();
        for (final Iterator<TimeEntry<MemorySegment>> inMemoryIterator : inMemoryDao.get(state, from, to)) {
            builder.addIterator(new WrappedIterator<>(id++, inMemoryIterator));
        }
        for (final Iterator<TimeEntry<MemorySegment>> outMemoryIterator : outMemoryDao.get(state, from, to)) {
            builder.addIterator(new WrappedIterator<>(id++, outMemoryIterator));
        }
        return builder.build();
    }

    @Override
    public TimeEntry<MemorySegment> get(final MemorySegment key) {
        Objects.requireNonNull(key);
        final State state = stateService.state();
        TimeEntry<MemorySegment> result = inMemoryDao.get(state, key);
        if (result == null) {
            result = outMemoryDao.get(state, key);
        }
        if (result == null || result.value() == null) {
            return null;
        }
        return result;
    }

    @Override
    public void upsert(final TimeEntry<MemorySegment> entry) {
        try {
            while (true) {
                final State state = stateService.state();
                if (inMemoryDao.upsert(state, entry)) {
                    return;
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void flush() throws IOException {
        inMemoryDao.flush();
    }

    @Override
    public void compact() {
        outMemoryDao.compact();
    }

    @Override
    public void close() throws IOException {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }
        inMemoryDao.close();
        outMemoryDao.close();
    }
}
