package ru.vk.itmo.test.shishiginstepan.dao;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PersistentStorage {
    private final Path basePath;
    private final NavigableSet<BinarySearchSSTable> sstables = new ConcurrentSkipListSet<>(
            Comparator.comparingInt(o -> -o.id)
    );
    private final AtomicInteger lastSSTableId;

    private final Arena arena;

    private static final ThreadLocal<List<BinarySearchSSTable>> tablesToCompact = new ThreadLocal<>();

    private static final class CompactionError extends RuntimeException {
        public CompactionError(Exception e) {
            super(e);
        }
    }

    PersistentStorage(Path basePath) {
        arena = Arena.ofShared();
        this.basePath = basePath;
        try (var sstablesFiles = Files.list(basePath)) {
            sstablesFiles.filter(
                    x -> !x.getFileName().toString().contains("_index") && !x.getFileName().toString().contains("tmp")
            ).map(
                    path -> new BinarySearchSSTable(path, arena)).forEach(this.sstables::add);
        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.WARNING, "Failed reading SSTABLE (probably deleted)");
        }

        lastSSTableId = new AtomicInteger(this.sstables.isEmpty() ? 0 : this.sstables.getFirst().id);
    }

    public synchronized int nextId() {
        return this.lastSSTableId.incrementAndGet();
    }

    public void close() {
        arena.close();
    }

    /**
     * Гарантирует что при успешном завершении записи на диск, SSTable с переданными в метод данными
     * сразу будет доступен для чтения в PersistentStorage.
     **/
    public void store(Iterable<Entry<MemorySegment>> data, int id) {
        BinarySearchSSTable newSSTable = BinarySearchSSTableWriter.writeSSTable(
                data,
                basePath,
                id,
                arena
        );
        this.sstables.add(newSSTable);
    }

    public Entry<MemorySegment> get(MemorySegment key) {
        for (BinarySearchSSTable sstable : this.sstables) {
            if (sstable.closed.get()) continue;
            Entry<MemorySegment> ssTableResult = sstable.get(key);
            if (ssTableResult != null) {
                return ssTableResult;
            }
        }
        return null;
    }

    public void enrichWithPersistentIterators(
            MemorySegment from,
            MemorySegment to,
            List<Iterator<Entry<MemorySegment>>> iteratorsToEnrich
    ) {
        iteratorsToEnrich.addAll(getPersistentIterators(from, to));
    }

    private List<Iterator<Entry<MemorySegment>>> getPersistentIterators(
            MemorySegment from,
            MemorySegment to
    ) {
        List<Iterator<Entry<MemorySegment>>> iterators = new ArrayList<>(sstables.size() + 1);
        for (var sstable : sstables) {
            if (sstable.closed.get()) continue;
            iterators.add(sstable.scan(from, to));
        }
        return iterators;
    }

    private void setTablesToCompact() {
        for (var sstable : sstables) {
            if (sstable.closed.get()) continue;
            if (sstable.inCompaction.compareAndSet(false, true)) tablesToCompact.get().add(sstable);
        }
    }

    private List<Iterator<Entry<MemorySegment>>> getCompactableIterators() {
        List<Iterator<Entry<MemorySegment>>> iteratorsToCompact = new ArrayList<>();
        for (var sstable : tablesToCompact.get()) {
            if (sstable.closed.get()) continue;
            if (sstable.inCompaction.compareAndSet(false, true)) tablesToCompact.get().add(sstable);
        }

        for (var sstable : tablesToCompact.get()) {
            iteratorsToCompact.add(sstable.scan(null, null));
        }
        tablesToCompact.remove();
        return iteratorsToCompact;
    }

    public void compact(int id) {
        tablesToCompact.set(new ArrayList<>());
        setTablesToCompact();
        store(
                () -> {
                    var iterators = getCompactableIterators();
                    return new SkipDeletedIterator(
                            new MergeIterator(
                                    iterators));
                }, id);
        for (var sstable : tablesToCompact.get()) {
            compactionClean(sstable);
        }
        tablesToCompact.remove();
    }

    private void compactionClean(BinarySearchSSTable sstable) {
        sstable.close();
        try {
            Files.delete(sstable.indexPath);
            Files.delete(sstable.tablePath);
        } catch (IOException e) {
            throw new CompactionError(e);
        }
    }
}
