package ru.vk.itmo.test.tyapuevdmitrij.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MemorySegmentDao implements Dao<MemorySegment, Entry<MemorySegment>> {
    private final Path ssTablePath;
    private final Config config;
    private State state;
    private final ExecutorService flusher =
            Executors.newSingleThreadExecutor(r -> {
                final Thread result = new Thread(r);
                result.setName("flusher");
                return result;
            });
    private final ExecutorService compactor =
            Executors.newSingleThreadExecutor(r -> {
                final Thread result = new Thread(r);
                result.setName("compactor");
                return result;
            });
    private final ReadWriteLock upsertLock = new ReentrantReadWriteLock();
    AtomicBoolean closed = new AtomicBoolean();

    public MemorySegmentDao(Config config) {
        ssTablePath = config.basePath();
        state = new State(new ConcurrentSkipListMap<>(MemorySegmentComparator.getMemorySegmentComparator()),
                null, new AtomicLong(),
                new Storage(ssTablePath));
        this.config = config;
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        State currentState = this.state;
        List<Iterator<Entry<MemorySegment>>> iterators = new ArrayList<>();
        iterators.add(getMemTableIterator(from, to, currentState.flushMemTable));
        iterators.add(getMemTableIterator(from, to, currentState.memTable));
        if (currentState.storage.ssTablesQuantity == 0) {
            return new MergeIterator(iterators, Comparator.comparing(Entry::key,
                    MemorySegmentComparator.getMemorySegmentComparator()));
        } else {
            return currentState.storage.range(iterators.get(0),
                    iterators.get(1),
                    from,
                    to,
                    MemorySegmentComparator.getMemorySegmentComparator());
        }
    }

    private Iterator<Entry<MemorySegment>> getMemTableIterator(MemorySegment from, MemorySegment to,
                                                               NavigableMap<MemorySegment,
                                                                       Entry<MemorySegment>> memTable) {
        if (memTable == null) {
            return Collections.emptyIterator();
        }
        if (from == null && to == null) {
            return memTable.values().iterator();
        }
        if (from == null) {
            return memTable.headMap(to).values().iterator();
        }
        if (to == null) {
            return memTable.tailMap(from).values().iterator();
        }
        return memTable.subMap(from, to).values().iterator();
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        State currentState = this.state;
        Entry<MemorySegment> value = currentState.memTable.get(key);
        if (value != null && value.value() == null) {
            return null;
        }
        if (value == null && currentState.flushMemTable != null) {
            value = currentState.flushMemTable.get(key);
        }
        if (value != null && value.value() == null) {
            return null;
        }
        if (value != null || currentState.storage.ssTables == null) {
            return value;
        }
        Iterator<Entry<MemorySegment>> iterator = currentState.storage.range(Collections.emptyIterator(),
                Collections.emptyIterator(),
                key,
                null,
                MemorySegmentComparator.getMemorySegmentComparator());

        if (!iterator.hasNext()) {
            return null;
        }
        Entry<MemorySegment> next = iterator.next();
        if (MemorySegmentComparator.getMemorySegmentComparator().compare(next.key(), key) == 0) {
            return next;
        }
        return null;
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        final boolean autoFlush;
        upsertLock.readLock().lock();
        try {
            if (state.memoryUsage.get() > config.flushThresholdBytes()
                    && state.flushMemTable != null) {
                throw new IllegalStateException("Can't keep up with flushing!");
            }

            // Upsert
            final Entry<MemorySegment> previous = state.memTable.put(entry.key(), entry);

            // Update size estimate
            final long size = state.memoryUsage.addAndGet(StorageHelper.sizeOf(entry) - StorageHelper.sizeOf(previous));
            autoFlush = size > config.flushThresholdBytes();
        } finally {
            upsertLock.readLock().unlock();
        }

        if (autoFlush) {
            flushing(true);
        }
    }

    @Override
    public void flush() throws IOException {
        flushing(false);
    }

    public void flushing(final boolean auto) {
        flusher.submit(() -> {
            final State currentState;
            upsertLock.writeLock().lock();
            try {
                if (this.state.memTable.isEmpty()) {
                    // Nothing to flush
                    return;
                }

                if (auto && this.state.memoryUsage.get() < config.flushThresholdBytes()) {
                    // Not enough data to flush
                    return;
                }

                // Switch memTable to flushing
                currentState = this.state.prepareToFlash();
                this.state = currentState;
            } finally {
                upsertLock.writeLock().unlock();
            }
            int ssTablesQuantity = state.storage.ssTablesQuantity;
            // Write
            try {
                state.storage.save(state.flushMemTable.values(), ssTablePath, state.storage);
            } catch (IOException e) {
                Runtime.getRuntime().halt(-1);
                return;
            }

            // Open
            final MemorySegment flushed;
            Path flushedPath = ssTablePath.resolve(StorageHelper.SS_TABLE_FILE_NAME + ssTablesQuantity);
            flushed = NmapBuffer.getReadBufferFromSsTable(flushedPath, state.storage.readArena);

            // Switch
            upsertLock.writeLock().lock();
            try {
                this.state = this.state.afterFLush(flushed);
            } finally {
                upsertLock.writeLock().unlock();
            }
        }).state();
    }

    @Override
    public void compact() throws IOException {
        State currentState = this.state;
        if (currentState.storage.ssTablesQuantity <= 1) {
            return;
        }
        Future<?> submit = compactor.submit(() -> {
            State stateNow = this.state;
            if (!currentState.storage.readArena.scope().isAlive()) {
                return;
            }
            stateNow.storage.storageHelper.saveDataForCompaction(stateNow, ssTablePath);
            stateNow.storage.storageHelper.deleteOldSsTables(ssTablePath);
            stateNow.storage.storageHelper.renameCompactedSsTable(ssTablePath);
            Storage load = new Storage(ssTablePath);
            upsertLock.writeLock().lock();
            try {
                this.state = new State(this.state.memTable, this.state.flushMemTable, state.memoryUsage, load);
            } finally {
                upsertLock.writeLock().unlock();
            }
        });
        if (submit.isCancelled()) {
            throw new DAOException("compact error");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed.getAndSet(true)) {
            return;
        }
        flush();
        flusher.close();
        compactor.close();

        // Close arena
        state.storage.readArena.close();
    }
}
