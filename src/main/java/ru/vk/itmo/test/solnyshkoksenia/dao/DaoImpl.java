package ru.vk.itmo.test.solnyshkoksenia.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.solnyshkoksenia.dao.storage.DiskStorage;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DaoImpl implements Dao<MemorySegment, Entry<MemorySegment>> {
    private static final Comparator<MemorySegment> comparator = new MemorySegmentComparator();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Arena arena;
    private final Path path;
    private volatile State curState;

    public DaoImpl(Config config) throws IOException {
        path = config.basePath().resolve("data");
        Files.createDirectories(path);

        arena = Arena.ofShared();

        this.curState = new State(config, new DiskStorage(DiskStorage.loadOrRecover(path, arena), path));
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        State state = this.curState.checkAndGet();
        List<Iterator<EntryExtended<MemorySegment>>> iterators = List.of(
                state.getInMemory(state.flushingStorage, from, to),
                state.getInMemory(state.storage, from, to)
        );

        Iterator<EntryExtended<MemorySegment>> iterator = new MergeIterator<>(iterators,
                (e1, e2) -> comparator.compare(e1.key(), e2.key()));
        Iterator<EntryExtended<MemorySegment>> innerIterator = state.diskStorage.range(iterator, from, to);

        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return innerIterator.hasNext();
            }

            @Override
            public Entry<MemorySegment> next() {
                return innerIterator.next();
            }
        };
    }

    public void upsert(Entry<MemorySegment> entry, Long ttl) {
        State state = this.curState.checkAndGet();

        lock.readLock().lock();
        try {
            state.putInMemory(entry, ttl);
        } finally {
            lock.readLock().unlock();
        }

        if (state.isOverflowed()) {
            try {
                autoFlush();
            } catch (IOException e) {
                throw new DaoException("Memory storage overflowed. Cannot flush", e);
            }
        }
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        upsert(entry, null);
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        State state = this.curState.checkAndGet();
        return state.get(key, comparator);
    }

    @Override
    public void flush() throws IOException {
        State state = this.curState.checkAndGet();
        if (state.storage.isEmpty() || state.isFlushing()) {
            return;
        }
        autoFlush();
    }

    private void autoFlush() throws IOException {
        State state = this.curState.checkAndGet();
        lock.writeLock().lock();
        try {
            if (state.isFlushing()) {
                return;
            }
            this.curState = state.moveStorage();
        } finally {
            lock.writeLock().unlock();
        }

        executor.execute(this::tryFlush);
    }

    private void tryFlush() {
        State state = this.curState.checkAndGet();
        try {
            state.flush();
        } catch (IOException e) {
            throw new DaoException("Flush failed", e);
        }

        lock.writeLock().lock();
        try {
            this.curState = new State(state.config, state.storage, new ConcurrentSkipListMap<>(comparator),
                    new DiskStorage(DiskStorage.loadOrRecover(path, arena), path));
        } catch (IOException e) {
            throw new DaoException("Cannot recover storage on disk", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void compact() {
        try {
            executor.submit(this::tryCompact).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DaoException("Compaction failed. Thread interrupted", e);
        } catch (ExecutionException e) {
            throw new DaoException("Compaction failed", e);
        }
    }

    private Object tryCompact() {
        State state = this.curState.checkAndGet();
        try {
            state.diskStorage.compact();
        } catch (IOException e) {
            throw new DaoException("Cannot compact", e);
        }

        lock.writeLock().lock();
        try {
            this.curState = new State(state.config, state.storage, state.flushingStorage,
                    new DiskStorage(DiskStorage.loadOrRecover(path, arena), path));
        } catch (IOException e) {
            throw new DaoException("Cannot recover storage on disk after compaction", e);
        } finally {
            lock.writeLock().unlock();
        }

        return null;
    }

    @Override
    public synchronized void close() throws IOException {
        State state = this.curState;
        if (state.isClosed() || !arena.scope().isAlive()) {
            return;
        }

        if (!state.storage.isEmpty()) {
            state.save();
        }

        executor.close();
        arena.close();

        this.curState = state.close();
    }
}
