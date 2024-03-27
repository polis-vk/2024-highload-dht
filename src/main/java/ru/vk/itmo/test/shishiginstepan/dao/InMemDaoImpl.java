package ru.vk.itmo.test.shishiginstepan.dao;

import ru.vk.itmo.dao.Dao;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class InMemDaoImpl implements Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> {
    private final ExecutorService executor;
    private final Lock flushLock = new ReentrantLock();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    Future<?> flushNotification;

    private static final Comparator<MemorySegment> keyComparator = (o1, o2) -> {
        long mismatch = o1.mismatch(o2);
        if (mismatch == -1) {
            return 0;
        }

        if (mismatch == o1.byteSize()) {
            return -1;
        }

        if (mismatch == o2.byteSize()) {
            return 1;
        }
        byte b1 = o1.get(ValueLayout.JAVA_BYTE, mismatch);
        byte b2 = o2.get(ValueLayout.JAVA_BYTE, mismatch);
        return Byte.compare(b1, b2);
    };

    private final AtomicLong memStorageSize = new AtomicLong(0);

    private final long memStorageLimit;

    private final AtomicReference<ConcurrentNavigableMap<MemorySegment, EntryWithTimestamp<MemorySegment>>> tempStorage =
            new AtomicReference<>(
                    new ConcurrentSkipListMap<>(
                            keyComparator
                    )
            );
    private final AtomicReference<ConcurrentNavigableMap<MemorySegment, EntryWithTimestamp<MemorySegment>>> memStorage =
            new AtomicReference<>(
                    new ConcurrentSkipListMap<>(
                            keyComparator
                    )
            );

    private final PersistentStorage persistentStorage;
    private final Path basePath;

    private static class MemStorageOverflowException extends RuntimeException {
    }

    private static class ShutdownInterruptedException extends RuntimeException {
        public ShutdownInterruptedException(Exception e) {
            super(e);
        }

        public ShutdownInterruptedException() {
            super();
        }
    }

    public InMemDaoImpl(Path basePath, long memStorageLimit) {
        this.basePath = basePath;
        this.persistentStorage = new PersistentStorage(this.basePath);
        this.memStorageLimit = memStorageLimit;
        this.executor = Executors.newFixedThreadPool(2);
    }

    public InMemDaoImpl() {
        this.basePath = Paths.get("./");
        this.persistentStorage = new PersistentStorage(this.basePath);
        this.memStorageLimit = Runtime.getRuntime().freeMemory();
        this.executor = Executors.newFixedThreadPool(2);
    }

    @Override
    public Iterator<EntryWithTimestamp<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        Iterator<EntryWithTimestamp<MemorySegment>> memIterator;
        Iterator<EntryWithTimestamp<MemorySegment>> tempIterator;
        if (to == null && from == null) {
            memIterator = this.memStorage.get().values().iterator();
            tempIterator = this.tempStorage.get().values().iterator();
        } else if (to == null) {
            memIterator = this.memStorage.get().tailMap(from).values().iterator();
            tempIterator = this.tempStorage.get().tailMap(from).sequencedValues().iterator();
        } else if (from == null) {
            memIterator = this.memStorage.get().headMap(to).values().iterator();
            tempIterator = this.tempStorage.get().headMap(to).values().iterator();
        } else {
            memIterator = this.memStorage.get().subMap(from, to).values().iterator();
            tempIterator = this.tempStorage.get().subMap(from, to).values().iterator();
        }

        List<Iterator<EntryWithTimestamp<MemorySegment>>> iterators = new ArrayList<>();
        iterators.add(memIterator);
        iterators.add(tempIterator);
        persistentStorage.enrichWithPersistentIterators(from, to, iterators);
        return new SkipDeletedIterator(
                new MergeIterator(iterators)
        );
    }

    // если этот метод возвращает могилу, это валидный объект Ентри с таймстемпом смерти (RIP 😔)
    @Override
    public EntryWithTimestamp<MemorySegment> get(MemorySegment key) {
        EntryWithTimestamp<MemorySegment> entry = this.memStorage.get().get(key);
        if (entry == null) {
            entry = this.tempStorage.get().get(key);
        }
        if (entry == null) {
            entry = persistentStorage.get(key);
        }
        if (entry == null) {
            return new EntryWithTimestamp<>(key, null, 0L); // у пустых значений timestamp ноль
        }
        return entry;
    }

    @Override
    public void upsert(EntryWithTimestamp<MemorySegment> entry) {
        this.memStorage.get().put(entry.key(), entry);
        this.memStorageSize.updateAndGet(
                (size) -> size + (entry.key().byteSize() + (entry.value() == null ? 0 : entry.value().byteSize()))
        );
        if (this.memStorageSize.get() >= this.memStorageLimit) {
            flush();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            this.flush();

            try {
                flushNotification.get();
            } catch (InterruptedException | ExecutionException e) {
                Thread.currentThread().interrupt();
                throw new ShutdownInterruptedException(e);
            }

            this.persistentStorage.close();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                        throw new ShutdownInterruptedException();
                    }
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void flush() {
        flushNotification = executor.submit(() -> {
            if (flushLock.tryLock()) {
                try {
                    if (!this.memStorage.get().isEmpty()) {
                        this.tempStorage.set(this.memStorage.get());
                        this.memStorage.set(new ConcurrentSkipListMap<>(keyComparator));
                        this.memStorageSize.set(0);
                        this.persistentStorage.store(this.tempStorage.get().values(), persistentStorage.nextId());
                    }
                } finally {
                    flushLock.unlock();
                }
            } else {
                throw new MemStorageOverflowException();
            }
        });
    }

    @Override
    public void compact() {
        int id = persistentStorage.nextId();
        executor.execute(() -> {
            try {
                if (flushNotification != null) {
                    flushNotification.get();
                }
            } catch (InterruptedException | ExecutionException e) {
                Thread.currentThread().interrupt();
            }
            persistentStorage.compact(id);
        });
    }
}
