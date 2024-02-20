package ru.vk.itmo.test.abramovilya.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DaoImpl implements Dao<MemorySegment, Entry<MemorySegment>> {
    private ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> map =
            new ConcurrentSkipListMap<>(DaoImpl::compareMemorySegments);
    private final AtomicLong memoryMapSize = new AtomicLong();
    private ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> flushingMap;
    private final Storage storage;
    private final AtomicBoolean isFlushing = new AtomicBoolean(false);
    private final long flushThresholdBytes;

    // readLock - пишем значение
    // writeLock - очищаем map
    private final ReadWriteLock mapUpsertExchangeLock = new ReentrantReadWriteLock();
    private final ExecutorService backgroundQueue = Executors.newSingleThreadExecutor();

    public DaoImpl(Config config) throws IOException {
        flushThresholdBytes = config.flushThresholdBytes();
        this.storage = new Storage(config);

        System.out.println(config.basePath());
    }

    Iterator<Entry<MemorySegment>> firstNsstablesIterator(int n) {
        return new DaoIterator(n,
                null,
                null,
                storage,
                Collections.emptyNavigableMap(),
                null);
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return new DaoIterator(storage.getTotalSStables(), from, to, storage, map, flushingMap);
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        var value = map.get(key);
        if (value != null) {
            if (value.value() != null) {
                return value;
            }
            return null;
        }
        if (flushingMap != null) {
            var flushingValue = flushingMap.get(key);

            if (flushingValue != null && flushingValue.value() != null) {
                return flushingValue;
            }
            if (flushingValue != null && flushingValue.value() == null) {
                return null;
            }
        }
        return storage.get(key);
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        long sizeToAdd = entry.key().byteSize() + 2 * Long.BYTES;
        if (entry.value() != null) {
            sizeToAdd += entry.value().byteSize();
        }
        mapUpsertExchangeLock.readLock().lock();
        try {
            map.put(entry.key(), entry);
            memoryMapSize.addAndGet(sizeToAdd);
        } finally {
            mapUpsertExchangeLock.readLock().unlock();
        }

        if (memoryMapSize.get() <= flushThresholdBytes) {
            return;
        }
        mapUpsertExchangeLock.writeLock().lock();
        try {
            if (memoryMapSize.get() <= flushThresholdBytes) {
                return;
            }
            if (isFlushing.compareAndSet(false, true)) {
                flushingMap = map;
                long size = memoryMapSize.get();
                renewMap();
                backgroundQueue.execute(() -> backgroundFlush(size));
            } else {
                throw new DaoException.DaoMemoryException(
                        "Upsert happened with no free space and flushing already executing");
            }
        } finally {
            mapUpsertExchangeLock.writeLock().unlock();
        }
    }

    @Override
    public void compact() {
        if (!isFlushing.get()) {
            backgroundQueue.execute(() -> {
                int totalSStables = storage.getTotalSStables();
                var iterator = firstNsstablesIterator(totalSStables);
                if (!iterator.hasNext()) {
                    return;
                }
                try {
                    storage.compact(iterator, firstNsstablesIterator(totalSStables), totalSStables);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    @Override
    public void flush() {
        if (isFlushing.compareAndSet(false, true)) {
            backgroundQueue.execute(() -> {
                long size;
                try {
                    mapUpsertExchangeLock.writeLock().lock();
                    try {
                        if (map.isEmpty()) {
                            return;
                        }
                        flushingMap = map;
                        size = memoryMapSize.get();
                        renewMap();
                    } finally {
                        mapUpsertExchangeLock.writeLock().unlock();
                    }
                    writeMapIntoFile(flushingMap, size);
                    storage.incTotalSStablesAmount();

                    flushingMap = null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    isFlushing.set(false);
                }
            });
        }
    }

    private void renewMap() {
        memoryMapSize.set(0);
        map = new ConcurrentSkipListMap<>(DaoImpl::compareMemorySegments);
    }

    private void backgroundFlush(long size) {
        try {
            writeMapIntoFile(flushingMap, size);
            storage.incTotalSStablesAmount();
            flushingMap = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            isFlushing.set(false);
        }
    }

    private void writeMapIntoFile(NavigableMap<MemorySegment, Entry<MemorySegment>> mapToWrite,
                                  long size) throws IOException {
        if (mapToWrite.isEmpty()) {
            return;
        }
        storage.writeMapIntoFile(
                size,
                indexByteSizeInFile(mapToWrite),
                mapToWrite
        );
    }

    private long indexByteSizeInFile(NavigableMap<MemorySegment, Entry<MemorySegment>> mapToWrite) {
        return (long) mapToWrite.size() * (Integer.BYTES + Long.BYTES);
    }

    @Override
    public void close() throws IOException {
        backgroundQueue.close();
        if (!map.isEmpty()) {
            flushingMap = map;
            writeMapIntoFile(flushingMap, memoryMapSize.get());
            storage.incTotalSStablesAmount();
        }
        storage.close();
    }

    public static int compareMemorySegments(MemorySegment segment1, MemorySegment segment2) {
        long offset = segment1.mismatch(segment2);
        if (offset == -1) {
            return 0;
        } else if (offset == segment1.byteSize()) {
            return -1;
        } else if (offset == segment2.byteSize()) {
            return 1;
        }
        return Byte.compare(segment1.get(ValueLayout.JAVA_BYTE, offset), segment2.get(ValueLayout.JAVA_BYTE, offset));
    }

    public static int compareMemorySegmentsUsingOffset(MemorySegment segment1,
                                                       MemorySegment segment2,
                                                       long segment2Offset,
                                                       long segment2Size) {
        long mismatch = MemorySegment.mismatch(segment1,
                0,
                segment1.byteSize(),
                segment2,
                segment2Offset,
                segment2Offset + segment2Size);
        if (mismatch == -1) {
            return 0;
        } else if (mismatch == segment1.byteSize()) {
            return -1;
        } else if (mismatch == segment2Size) {
            return 1;
        }
        return Byte.compare(segment1.get(ValueLayout.JAVA_BYTE, mismatch),
                segment2.get(ValueLayout.JAVA_BYTE, segment2Offset + mismatch));
    }
}
