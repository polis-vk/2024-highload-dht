package ru.vk.itmo.test.grunskiialexey.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MemorySegmentDao implements Dao<MemorySegment, Entry<MemorySegment>> {
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    private static final Comparator<MemorySegment> comparator = MemorySegmentDao::compare;
    private final Arena arena;
    private final Path path;
    private final long flushThresholdBytes;
    private final AtomicReference<StorageState> state;
    private final AtomicLong memTableSize = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReadWriteLock upsertLock = new ReentrantReadWriteLock();

    public MemorySegmentDao(Config config) throws IOException {
        this.flushThresholdBytes = config.flushThresholdBytes();
        this.path = config.basePath().resolve("data");
        Files.createDirectories(path);

        arena = Arena.ofShared();
        List<MemorySegment> segments = DiskStorage.loadOrRecover(path, arena);
        state = new AtomicReference<>(StorageState.initial(segments));
    }

    static int compare(MemorySegment memorySegment1, MemorySegment memorySegment2) {
        long mismatch = memorySegment1.mismatch(memorySegment2);
        if (mismatch == -1) {
            return 0;
        }

        if (mismatch == memorySegment1.byteSize()) {
            return -1;
        }

        if (mismatch == memorySegment2.byteSize()) {
            return 1;
        }
        byte b1 = memorySegment1.get(ValueLayout.JAVA_BYTE, mismatch);
        byte b2 = memorySegment2.get(ValueLayout.JAVA_BYTE, mismatch);
        return Byte.compare(b1, b2);
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        StorageState currentState = this.state.get();

        return Compaction.range(
                getInMemory(currentState.readStorage, from, to),
                getInMemory(currentState.writeStorage, from, to),
                currentState.diskSegmentList,
                from,
                to
        );
    }

    private Iterator<Entry<MemorySegment>> getInMemory(
            ConcurrentSkipListMap<MemorySegment, Entry<MemorySegment>> storage,
            MemorySegment from, MemorySegment to) {
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

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        final boolean autoFlush;
        upsertLock.readLock().lock();
        try {

            // Upsert
            final Entry<MemorySegment> prev =
                    state.get().writeStorage.put(entry.key(), entry);

            // Update size estimate
            final long size = memTableSize.addAndGet(DiskStorage.sizeOf(entry) - DiskStorage.sizeOf(prev));
            autoFlush = size > flushThresholdBytes;
        } finally {
            upsertLock.readLock().unlock();
        }

        if (autoFlush) {
            initializeFlush(true);
        }
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        synchronized (this) {
            StorageState currentState = this.state.get();
            Entry<MemorySegment> entry = currentState.writeStorage.get(key);
            if (entry != null) {
                if (entry.value() == null) {
                    return null;
                }
                return entry;
            }

            Iterator<Entry<MemorySegment>> iterator = Compaction.range(
                    getInMemory(currentState.readStorage, key, null),
                    getInMemory(currentState.writeStorage, key, null),
                    currentState.diskSegmentList,
                    key,
                    null);

            if (!iterator.hasNext()) {
                return null;
            }
            Entry<MemorySegment> next = iterator.next();
            if (compare(next.key(), key) == 0) {
                return next;
            }
            return null;
        }
    }

    @Override
    public void flush() {
        initializeFlush(false);
    }

    private void initializeFlush(boolean auto) {
        bgExecutor.execute(() -> {
            Collection<Entry<MemorySegment>> entries;
            StorageState prevState = state.get();
            ConcurrentSkipListMap<MemorySegment, Entry<MemorySegment>> writeStorage = prevState.writeStorage;
            StorageState nextState;
            upsertLock.writeLock().lock();
            try {
                if (writeStorage.isEmpty()) {
                    // Nothing to flush
                    return;
                }

                if (auto && memTableSize.get() < flushThresholdBytes) {
                    // Not enough data to flush
                    return;
                }
                memTableSize.set(0);

                nextState = prevState.beforeFlush();

                state.set(nextState);
            } finally {
                upsertLock.writeLock().unlock();
            }

            MemorySegment newPage;
            entries = writeStorage.values();
            try {
                newPage = DiskStorage.save(arena, path, entries);
            } catch (IOException e) {
                // termination sequence
                throw new UncheckedIOException(e);
            }

            nextState = nextState.afterFlush(newPage);
            upsertLock.writeLock().lock();
            try {
                state.set(nextState);
            } finally {
                upsertLock.writeLock().unlock();
            }
        });
    }

    @Override
    public void compact() {
        bgExecutor.execute(() -> {
            synchronized (this) {
                try {
                    StorageState currentState = this.state.get();
                    MemorySegment newPage = Compaction.compact(arena, path, () -> Compaction.range(
                            Collections.emptyIterator(),
                            Collections.emptyIterator(),
                            currentState.diskSegmentList,
                            null,
                            null
                    ));
                    this.state.set(currentState.compact(newPage));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    @Override
    public void close() throws IOException {
        if (closed.getAndSet(true)) {
            waitForClose();
            return;
        }

        flush();
        bgExecutor.execute(arena::close);
        bgExecutor.shutdown();
        waitForClose();
    }

    private void waitForClose() throws InterruptedIOException {
        try {
            if (!bgExecutor.awaitTermination(11, TimeUnit.MINUTES)) {
                throw new InterruptedException("Timeout");
            }
        } catch (InterruptedException e) {
            InterruptedIOException exception = new InterruptedIOException("Interrupted or timed out");
            exception.initCause(e);
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static class StorageState {
        private final ConcurrentSkipListMap<MemorySegment, Entry<MemorySegment>> readStorage;
        private final ConcurrentSkipListMap<MemorySegment, Entry<MemorySegment>> writeStorage;
        private final List<MemorySegment> diskSegmentList;

        private StorageState(
                ConcurrentSkipListMap<MemorySegment, Entry<MemorySegment>> readStorage,
                ConcurrentSkipListMap<MemorySegment, Entry<MemorySegment>> writeStorage,
                List<MemorySegment> diskSegmentList
        ) {
            this.readStorage = readStorage;
            this.writeStorage = writeStorage;
            this.diskSegmentList = diskSegmentList;
        }

        private static ConcurrentSkipListMap<MemorySegment, Entry<MemorySegment>> createMap() {
            return new ConcurrentSkipListMap<>(comparator);
        }

        public static StorageState initial(List<MemorySegment> segments) {
            return new StorageState(
                    createMap(),
                    createMap(),
                    segments
            );
        }

        public StorageState compact(MemorySegment compacted) {
            return new StorageState(readStorage, writeStorage, Collections.singletonList(compacted));
        }

        public StorageState beforeFlush() {
            return new StorageState(writeStorage, createMap(), diskSegmentList);
        }

        public StorageState afterFlush(MemorySegment newPage) {
            List<MemorySegment> segments = new ArrayList<>(diskSegmentList.size() + 1);
            segments.addAll(diskSegmentList);
            segments.add(newPage);
            return new StorageState(createMap(), writeStorage, segments);
        }
    }
}
