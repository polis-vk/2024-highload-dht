package ru.vk.itmo.test.kislovdanil.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kislovdanil.dao.exceptions.DBException;
import ru.vk.itmo.test.kislovdanil.dao.exceptions.OverloadException;
import ru.vk.itmo.test.kislovdanil.dao.iterators.DatabaseIterator;
import ru.vk.itmo.test.kislovdanil.dao.iterators.MemTableIterator;
import ru.vk.itmo.test.kislovdanil.dao.iterators.MergeIterator;
import ru.vk.itmo.test.kislovdanil.dao.sstable.SSTable;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PersistentDao implements Dao<MemorySegment, Entry<MemorySegment>>, Iterable<Entry<MemorySegment>> {

    public static final MemorySegment DELETED_VALUE = null;
    private final Config config;
    private volatile List<SSTable> tables = new ArrayList<>();
    private final Comparator<MemorySegment> comparator = new MemSegComparator();
    private volatile MemTable memTable;
    // Temporary storage in case of main storage flushing (Read only)
    private volatile MemTable additionalStorage;
    // In case of additional table overload while main table is flushing
    private final AtomicLong nextId = new AtomicLong();
    private final ExecutorService commonExecutorService = Executors.newFixedThreadPool(2);
    // To prevent parallel flushing
    private volatile Future<?> compcatFuture;
    // To make sure that flushing in close() will be started
    private volatile Future<?> flushFuture;
    // Have to take before any tables modification
    private final Lock compactionLock = new ReentrantLock();
    // Have to take read while upsert and write while flushing (to prevent data loss)
    private final ReadWriteLock upsertLock = new ReentrantReadWriteLock();
    private final Arena filesArena = Arena.ofShared();
    // Limit for SSTables on drive
    private static final int COMPACTION_THRESHOLD = 5;

    private long getMaxTablesId(Iterable<SSTable> tableIterable) {
        long curMaxId = -1;
        for (SSTable table : tableIterable) {
            curMaxId = Math.max(curMaxId, table.getTableId());
        }
        return curMaxId;
    }

    public PersistentDao(Config config) throws IOException {
        this.config = config;
        this.memTable = new MemTable(comparator, config.flushThresholdBytes());
        File basePathDirectory = new File(config.basePath().toString());
        String[] ssTablesIds = basePathDirectory.list();
        if (ssTablesIds == null) return;
        for (String tableID : ssTablesIds) {
            // SSTable constructor without entries iterator reads table data from disk if it exists
            tables.add(new SSTable(config.basePath(), comparator, Long.parseLong(tableID), filesArena));
        }
        nextId.set(getMaxTablesId(tables) + 1);
        tables.sort(SSTable::compareTo);
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        List<DatabaseIterator> iterators = new ArrayList<>(tables.size() + 2);
        for (SSTable table : tables) {
            iterators.add(table.getRange(from, to));
        }
        iterators.add(new MemTableIterator(from, to, memTable, Long.MAX_VALUE));
        if (additionalStorage != null) {
            iterators.add(new MemTableIterator(from, to, additionalStorage, Long.MAX_VALUE - 1));
        }
        return new MergeIterator(iterators, comparator);
    }

    private static Entry<MemorySegment> wrapEntryIfDeleted(Entry<MemorySegment> entry) {
        if (entry.value() == DELETED_VALUE) return null;
        return entry;
    }

    private long getNextId() {
        return nextId.getAndIncrement();
    }

    // Return null if it doesn't find
    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        Entry<MemorySegment> ans = memTable.getStorage().get(key);
        if (ans != null) return wrapEntryIfDeleted(ans);
        if (additionalStorage != null) {
            ans = additionalStorage.getStorage().get(key);
            if (ans != null) return wrapEntryIfDeleted(ans);
        }
        try {
            for (SSTable table : tables.reversed()) {
                ans = table.find(key);
                if (ans != null) {
                    return wrapEntryIfDeleted(ans);
                }
            }
        } catch (IOException e) {
            throw new DBException(e);
        }
        return null;
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        upsertLock.readLock().lock();
        try {
            if (memTable.put(entry)) {
                return;
            }
        } finally {
            upsertLock.readLock().unlock();
        }
        flush();
        upsertLock.readLock().lock();
        try {
            if (!memTable.put(entry)) {
                throw new OverloadException(entry);
            }
        } finally {
            upsertLock.readLock().unlock();
        }
    }

    private void makeFlush() throws IOException {
        compactionLock.lock();
        try {
            if (additionalStorage == null) return;
            // SSTable constructor with entries iterator writes MemTable data on disk deleting old data if it exists
            tables.add(new SSTable(config.basePath(), comparator,
                    getNextId(), additionalStorage.getStorage().values().iterator(), filesArena));
            if (tables.size() > COMPACTION_THRESHOLD) {
                compact();
            }
            additionalStorage = null;
        } finally {
            compactionLock.unlock();
        }
    }

    @Override
    public void flush() {
        upsertLock.writeLock().lock();
        try {
            if (additionalStorage != null || memTable.getStorage().isEmpty()) {
                return;
            }
            additionalStorage = memTable;
            memTable = new MemTable(comparator, config.flushThresholdBytes());
            flushFuture = commonExecutorService.submit(
                    () -> {
                        try {
                            makeFlush();
                        } catch (IOException e) {
                            throw new DBException(e);
                        }
                    });
        } finally {
            upsertLock.writeLock().unlock();
        }
    }

    private void closeExecutorService(ExecutorService executorService) {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (!filesArena.scope().isAlive()) {
            return;
        }
        if (flushFuture != null) {
            try {
                flushFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
        closeExecutorService(commonExecutorService);
        filesArena.close();
    }

    private void makeCompaction() throws IOException {
        compactionLock.lock();
        try {
            if (tables.size() <= 1) return;
            long compactedTableId = getNextId();
            SSTable compactedTable = new SSTable(config.basePath(), comparator, compactedTableId,
                    new MergeIterator(tables, comparator), filesArena);
            List<SSTable> oldTables = tables;
            List<SSTable> newTables = new ArrayList<>();
            newTables.add(compactedTable);
            tables = newTables;
            for (SSTable table : oldTables) {
                table.deleteFromDisk();
            }
        } finally {
            compactionLock.unlock();
        }
    }

    @Override
    public void compact() {
        if (compcatFuture != null && !compcatFuture.isDone()) {
            compcatFuture.cancel(false);
        }
        compcatFuture = commonExecutorService.submit(
                () -> {
                    try {
                        makeCompaction();
                    } catch (IOException e) {
                        throw new DBException(e);
                    }
                });
    }

    @Override
    public Iterator<Entry<MemorySegment>> iterator() {
        return get(null, null);
    }

    private static class MemSegComparator implements Comparator<MemorySegment> {
        @Override
        public int compare(MemorySegment o1, MemorySegment o2) {
            long mismatch = o1.mismatch(o2);
            if (mismatch == -1) {
                return 0;
            }
            if (mismatch == Math.min(o1.byteSize(), o2.byteSize())) {
                return Long.compare(o1.byteSize(), o2.byteSize());
            }
            return Byte.compare(o1.get(ValueLayout.JAVA_BYTE, mismatch), o2.get(ValueLayout.JAVA_BYTE, mismatch));
        }
    }
}
