package ru.vk.itmo.test.chebotinalexandr.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static ru.vk.itmo.test.chebotinalexandr.dao.SSTableUtils.SS_TABLE_PRIORITY;
import static ru.vk.itmo.test.chebotinalexandr.dao.SSTableUtils.sizeOf;

public class NotOnlyInMemoryDao implements Dao<MemorySegment, Entry<MemorySegment>> {
    private static final double BLOOM_FILTER_FPP = 0.03;
    private final SSTablesStorage ssTablesStorage;
    private final Config config;
    private final Arena arena;
    private final AtomicReference<State> state;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReadWriteLock upsertLock = new ReentrantReadWriteLock();
    private final AtomicLong size = new AtomicLong();

    public static int comparator(MemorySegment segment1, MemorySegment segment2) {
        long offset = segment1.mismatch(segment2);

        if (offset == -1) {
            return 0;
        }
        if (offset == segment1.byteSize()) {
            return -1;
        }
        if (offset == segment2.byteSize()) {
            return 1;
        }

        return Byte.compare(
                segment1.get(ValueLayout.JAVA_BYTE, offset),
                segment2.get(ValueLayout.JAVA_BYTE, offset)
        );
    }

    public static int entryComparator(Entry<MemorySegment> entry1, Entry<MemorySegment> entry2) {
        return comparator(entry1.key(), entry2.key());
    }

    public NotOnlyInMemoryDao(Config config) {
        this.config = config;
        arena = Arena.ofShared();
        Path path = config.basePath();
        List<MemorySegment> segments = SSTablesStorage.loadOrRecover(path, arena);
        ssTablesStorage = new SSTablesStorage(path);
        state = new AtomicReference<>(State.initial(segments));
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        State currState = this.state.get();

        PeekingIterator<Entry<MemorySegment>> rangeIterator = range(
                memoryIterator(currState.getReadEntries(), from, to),
                memoryIterator(currState.getWriteEntries(), from, to),
                currState.getSstables(),
                from, to);
        return new SkipTombstoneIterator(rangeIterator);
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        State currState = this.state.get();

        Entry<MemorySegment> result = currState.getWriteEntries().get(key);
        if (result != null) {
            return result.value() == null ? null : result;
        }
        result = currState.getReadEntries().get(key);
        if (result != null) {
            return result.value() == null ? null : result;
        }

        return getFromDisk(key, currState);
    }

    private Entry<MemorySegment> getFromDisk(MemorySegment key, State state) {
        Entry<MemorySegment> result;

        for (MemorySegment sstable : state.getSstables()) {
            boolean mayContain = BloomFilter.sstableMayContain(key, sstable);
            if (mayContain) {
                result = SSTableUtils.get(sstable, key);

                if (result != null) {
                    return result.value() == null ? null : result;
                }
            }
        }

        return null;
    }

    /**
     * Merges iterators.
     */
    private PeekingIterator<Entry<MemorySegment>> range(Iterator<Entry<MemorySegment>> firstIterator,
            Iterator<Entry<MemorySegment>> secondIterator,
            List<MemorySegment> segments,
            MemorySegment from,
            MemorySegment to) {

        List<PeekingIterator<Entry<MemorySegment>>> iterators = new ArrayList<>();

        iterators.add(new PeekingIteratorImpl<>(firstIterator, 1));
        iterators.add(new PeekingIteratorImpl<>(secondIterator, 0));
        iterators.add(new PeekingIteratorImpl<>(SSTablesStorage.iteratorsAll(segments, from, to), SS_TABLE_PRIORITY));

        return new PeekingIteratorImpl<>(MergeIterator.merge(iterators, NotOnlyInMemoryDao::entryComparator));
    }

    private PeekingIterator<Entry<MemorySegment>> iteratorForCompaction(List<MemorySegment> segments) {
        return new PeekingIteratorImpl<>(SSTablesStorage.iteratorsAll(segments, null, null));
    }

    private static Iterator<Entry<MemorySegment>> memoryIterator(
            SortedMap<MemorySegment, Entry<MemorySegment>> entries,
            MemorySegment from,
            MemorySegment to
    ) {
        if (from == null && to == null) {
            return entries.values().iterator();
        } else if (from == null) {
            return entries.headMap(to).values().iterator();
        } else if (to == null) {
            return entries.tailMap(from).values().iterator();
        } else {
            return entries.subMap(from, to).values().iterator();
        }
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        final boolean autoFlush;
        MemorySegment key = entry.key();

        Entry<MemorySegment> old;
        upsertLock.readLock().lock();
        try {
            old = state.get().getWriteEntries().put(key, entry);

            final long curSize = size.addAndGet(sizeOf(entry) - sizeOf(old));
            autoFlush = curSize > config.flushThresholdBytes();
        } finally {
            upsertLock.readLock().unlock();
        }

        if (autoFlush) {
            flush();
        }
    }

    @Override
    public void compact() {
        bgExecutor.execute(() -> {
            try {
                State currState = this.state.get();
                MemorySegment newPage = performCompact(currState.getSstables());
                this.state.set(currState.compact(newPage));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private MemorySegment performCompact(List<MemorySegment> segments) throws IOException {
        Iterator<Entry<MemorySegment>> iterator = new SkipTombstoneIterator(iteratorForCompaction(segments));

        long sizeForCompaction = 0;
        long entryCount = 0;
        long nonEmptyEntryCount = 0;
        while (iterator.hasNext()) {
            Entry<MemorySegment> entry = iterator.next();
            sizeForCompaction += entry.key().byteSize();
            MemorySegment value = entry.value();
            if (value != null) {
                sizeForCompaction += value.byteSize();
                nonEmptyEntryCount++;
            }
            entryCount++;
        }

        if (entryCount == 0) {
            return null;
        }

        long newBloomFilterLength = BloomFilter.bloomFilterLength(entryCount, BLOOM_FILTER_FPP);

        sizeForCompaction += 2L * Long.BYTES * nonEmptyEntryCount;
        sizeForCompaction += 3L * Long.BYTES + Long.BYTES * nonEmptyEntryCount; //for metadata (header + key offsets)
        sizeForCompaction += Long.BYTES * newBloomFilterLength; //for bloom filter

        iterator = new SkipTombstoneIterator(iteratorForCompaction(segments));
        return ssTablesStorage.compact(iterator, sizeForCompaction, nonEmptyEntryCount, newBloomFilterLength);
    }

    @Override
    public void flush() {
        bgExecutor.execute(() -> {
            State prevState = state.get();
            SortedMap<MemorySegment, Entry<MemorySegment>> writeEntries = prevState.getWriteEntries();
            if (writeEntries.isEmpty()) {
                return;
            }

            State nextState = prevState.beforeFlush();
            upsertLock.writeLock().lock();
            try {
                state.set(nextState);
            } finally {
                upsertLock.writeLock().unlock();
            }

            Collection<Entry<MemorySegment>> toFlush;
            MemorySegment newPage;
            toFlush = writeEntries.values();
            try {
                newPage = ssTablesStorage.write(toFlush, BLOOM_FILTER_FPP);
            } catch (IOException e) {
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

    private void waitForClose() {
        try {
            if (!bgExecutor.awaitTermination(5, TimeUnit.MINUTES)) {
                throw new InterruptedException("Timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
