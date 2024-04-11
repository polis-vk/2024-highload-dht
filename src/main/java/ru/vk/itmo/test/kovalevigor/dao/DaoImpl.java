package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalevigor.dao.entry.DaoEntry;
import ru.vk.itmo.test.kovalevigor.dao.iterators.MemEntryPriorityIterator;
import ru.vk.itmo.test.kovalevigor.dao.iterators.MergeEntryIterator;
import ru.vk.itmo.test.kovalevigor.dao.iterators.PriorityShiftedIterator;
import ru.vk.itmo.test.kovalevigor.utils.IOFunction;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DaoImpl<T extends DaoEntry<MemorySegment>> implements Dao<MemorySegment, T> {

    private final SSTableManager<T> ssManager;
    private final ConcurrentNavigableMap<MemorySegment, T> emptyMap =
            new ConcurrentSkipListMap<>(SSTable.COMPARATOR);
    private ConcurrentNavigableMap<MemorySegment, T> flushedStorage;
    private ConcurrentNavigableMap<MemorySegment, T> currentStorage;
    private final AtomicLong currentMemoryByteSize;
    private final long flushThresholdBytes;
    private final ExecutorService flushService;
    private final ExecutorService compactService;
    private Future<Void> flushFuture;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public DaoImpl(final Config config, IOFunction<Path, SSTableManager<T>> tableManagerFunction) throws IOException {
        ssManager = tableManagerFunction.apply(config.basePath());
        currentStorage = new ConcurrentSkipListMap<>(SSTable.COMPARATOR);
        flushedStorage = emptyMap;
        flushThresholdBytes = config.flushThresholdBytes();
        flushService = Executors.newSingleThreadExecutor();
        compactService = Executors.newSingleThreadExecutor();
        currentMemoryByteSize = new AtomicLong();
    }

    private static <T> Iterator<T> getValuesIterator(final SortedMap<?, T> map) {
        return map.values().iterator();
    }

    private static <E extends Entry<MemorySegment>> Iterator<E> getIterator(
            final SortedMap<MemorySegment, E> sortedMap,
            final MemorySegment from,
            final MemorySegment to
    ) {
        if (from == null) {
            if (to == null) {
                return getValuesIterator(sortedMap);
            } else {
                return getValuesIterator(sortedMap.headMap(to));
            }
        } else if (to == null) {
            return getValuesIterator(sortedMap.tailMap(from));
        } else {
            return getValuesIterator(sortedMap.subMap(from, to));
        }
    }

    @Override
    public Iterator<T> get(final MemorySegment from, final MemorySegment to) {
        final List<PriorityShiftedIterator<T>> iterators = new ArrayList<>(3);
        lock.readLock().lock();
        try {
            iterators.add(new MemEntryPriorityIterator<>(0, getIterator(currentStorage, from, to)));
            iterators.add(new MemEntryPriorityIterator<>(1, getIterator(flushedStorage, from, to)));
        } finally {
            lock.readLock().unlock();
        }
        try {
            iterators.add(new MemEntryPriorityIterator<>(2, ssManager.get(from, to)));
        } catch (IOException e) {
            log(e);
        }
        return new MergeEntryIterator<>(iterators);
    }

    private static long getMemorySegmentSize(final MemorySegment memorySegment) {
        return memorySegment == null ? 0 : memorySegment.byteSize();
    }

    private static <E extends Entry<MemorySegment>> long getTimeEntrySize(final E entry) {
        return getMemorySegmentSize(entry.key()) + getMemorySegmentSize(entry.value());
    }

    @Override
    public void upsert(final T entry) {
        Objects.requireNonNull(entry);
        final long entrySize = getTimeEntrySize(entry);

        lock.readLock().lock();
        try {
            currentStorage.merge(
                    entry.key(), entry, ssManager::mergeEntries
            );
            currentMemoryByteSize.addAndGet(entrySize);
        } finally {
            lock.readLock().unlock();
        }
        final long newSize = currentMemoryByteSize.get() + entrySize;
        if (newSize >= flushThresholdBytes) {
            if (!flushedStorage.isEmpty()) {
                throw new IllegalStateException("Limit is reached. U should wait");
            }
            flush();
        }
    }

    @Override
    public T get(final MemorySegment key) {
        Objects.requireNonNull(key);
        T result;
        lock.readLock().lock();
        try {
            result = currentStorage.get(key);
            if (result == null) {
                result = flushedStorage.get(key);
            }
        } finally {
            lock.readLock().unlock();
        }

        if (result != null) {
            if (ssManager.shouldBeNull(result)) {
                return null;
            }
            return result;
        }

        try {
            return ssManager.get(key);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void flush() {
        if (!flushedStorage.isEmpty()) {
            return;
        }

        final ConcurrentNavigableMap<MemorySegment, T> storage =
                new ConcurrentSkipListMap<>(SSTable.COMPARATOR);
        lock.writeLock().lock();
        try {

            if (currentStorage.isEmpty()) {
                return;
            }

            flushedStorage = currentStorage;
            currentStorage = storage;
            currentMemoryByteSize.set(0);
        } finally {
            lock.writeLock().unlock();
        }

        flushFuture = flushService.submit(() -> {
            String name = null;
            try {
                 name = ssManager.write(flushedStorage);
            } catch (IOException e) {
                log(e);
            } finally {
                lock.writeLock().lock();
                try {
                    if (name != null) {
                        ssManager.addSSTable(name);
                    }
                    flushedStorage = emptyMap;
                } catch (IOException e) {
                    log(e);
                } finally {
                    lock.writeLock().unlock();
                }
            }
        }, null);
    }

    private static void awaitShutdown(ExecutorService service) {
        while (true) {
            try {
                if (service.awaitTermination(1, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                log(e);
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void compact() throws IOException {
        compactService.execute(() -> {
            try {
                ssManager.compact();
            } catch (IOException e) {
                log(e);
            }
        });
    }

    @Override
    public void close() throws IOException {
        try {
            if (flushFuture != null) {
                flushFuture.get();
            }
        } catch (InterruptedException e) {
            log(e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log(e);
        }
        flush();
        compactService.shutdown();
        flushService.shutdown();
        awaitShutdown(compactService);
        awaitShutdown(flushService);
        currentStorage.clear();
        flushedStorage.clear();
        ssManager.close();
    }

    private static void log(Exception e) {
        if (Logger.getAnonymousLogger().isLoggable(Level.WARNING)) {
            Logger.getAnonymousLogger().log(Level.WARNING, Arrays.toString(e.getStackTrace()));
        }
    }
}
