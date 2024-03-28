package ru.vk.itmo.test.asvistukhin.dao;

import ru.vk.itmo.dao.Config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PersistentDao implements Dao<MemorySegment, TimestampEntry<MemorySegment>> {

    private final StorageState storageState;
    private final Arena arena;
    private final DiskStorage diskStorage;
    private final ConfigWrapper config;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public PersistentDao(Config config) throws IOException {
        this.config = new ConfigWrapper(
                config.flushThresholdBytes(),
                config.basePath().resolve("data"),
                config.basePath().resolve("compact_temp")
        );

        Files.createDirectories(this.config.getDataPath());
        Files.deleteIfExists(this.config.getCompactTempPath());

        arena = Arena.ofShared();

        this.diskStorage = new DiskStorage(DiskStorage.loadOrRecover(this.config.getDataPath(), arena));
        this.storageState = StorageState.initStorageState();
    }

    @Override
    public Iterator<TimestampEntry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return diskStorage.range(storageState, from, to);
    }

    @Override
    public void upsert(TimestampEntry<MemorySegment> entry) {
        if (storageState.getActiveSSTable().getStorageSize() >= config.getFlushThresholdBytes()
                && storageState.getFlushingSSTable() != null
        ) {
            throw new IllegalStateException("SSTable is full. Wait until flush.");
        }

        lock.writeLock().lock();
        try {
            storageState.getActiveSSTable().upsert(entry);
        } finally {
            lock.writeLock().unlock();
        }

        try {
            if (storageState.getActiveSSTable().getStorageSize() >= config.getFlushThresholdBytes()) {
                flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public TimestampEntry<MemorySegment> get(MemorySegment key) {
        TimestampEntry<MemorySegment> entry = storageState.getActiveSSTable().get(key);
        if (entry != null) {
            if (entry.value() == null) {
                return null;
            }
            return entry;
        }

        Iterator<TimestampEntry<MemorySegment>> iterator = diskStorage.rangeFromDisk(key, null);

        if (!iterator.hasNext()) {
            return null;
        }
        TimestampEntry<MemorySegment> next = iterator.next();
        if (MemorySegmentUtils.isSameKey(next.key(), key)) {
            return next;
        }

        return null;
    }

    @Override
    public synchronized void compact() {
        if (!arena.scope().isAlive()) {
            throw new IllegalStateException("DAO is closed.");
        }

        executor.execute(() -> {
            try {
                Iterable<TimestampEntry<MemorySegment>> compactValues = () -> diskStorage.rangeFromDisk(null, null);
                if (compactValues.iterator().hasNext()) {
                    Files.createDirectories(config.getCompactTempPath());
                    DiskStorage.save(config.getCompactTempPath(), compactValues);
                    DiskStorage.deleteObsoleteData(config.getDataPath());
                    Files.move(config.getCompactTempPath(), config.getDataPath(), StandardCopyOption.ATOMIC_MOVE);
                    diskStorage.mapSSTableAfterCompaction(config.getDataPath(), arena);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public synchronized void flush() throws IOException {
        if (!storageState.isReadyForFlush()) {
            return;
        }

        storageState.prepareStorageForFlush();
        executor.execute(() -> {
            lock.writeLock().lock();
            try {
                flushToDisk();
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    @Override
    public synchronized void close() {
        if (!arena.scope().isAlive()) {
            return;
        }

        try {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (storageState.isReadyForFlush()) {
                storageState.prepareStorageForFlush();
                flushToDisk();
            }
            arena.close();
        }
    }

    private void flushToDisk() {
        try {
            Iterable<TimestampEntry<MemorySegment>> valuesToFlush = () -> storageState.getFlushingSSTable().getAll();
            String filename = DiskStorage.save(config.getDataPath(), valuesToFlush);
            storageState.removeFlushingSSTable();
            diskStorage.mapSSTableAfterFlush(config.getDataPath(), filename, arena);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
