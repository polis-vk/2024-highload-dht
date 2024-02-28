package ru.vk.itmo.test.timofeevkirill.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Reference implementation of {@link Dao}.
 *
 * @author incubos
 */
public class ReferenceDao implements Dao<MemorySegment, Entry<MemorySegment>> {
    private final Config config;
    private final Arena arena;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    // Guarded by lock
    private volatile TableSet tableSet;

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

    private final AtomicBoolean closed = new AtomicBoolean();

    public ReferenceDao(final Config config) throws IOException {
        this.config = config;
        this.arena = Arena.ofShared();

        // First complete promotion of compacted SSTables
        SSTables.promote(
                config.basePath(),
                0,
                1);

        this.tableSet =
                TableSet.from(
                        SSTables.discover(
                                arena,
                                config.basePath()));
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(
            final MemorySegment from,
            final MemorySegment to) {
        return new LiveFilteringIterator(
                tableSet.get(
                        from,
                        to));
    }

    @Override
    public Entry<MemorySegment> get(final MemorySegment key) {
        // Without lock, just snapshot of table set
        return tableSet.get(key);
    }

    @Override
    public void upsert(final Entry<MemorySegment> entry) {
        final boolean autoFlush;
        lock.readLock().lock();
        try {
            if (tableSet.memTableSize.get() > config.flushThresholdBytes()
                    && tableSet.flushingTable != null) {
                throw new IllegalStateException("Can't keep up with flushing!");
            }

            // Upsert
            final Entry<MemorySegment> previous = tableSet.upsert(entry);

            // Update size estimate
            final long size = tableSet.memTableSize.addAndGet(sizeOf(entry) - sizeOf(previous));
            autoFlush = size > config.flushThresholdBytes();
        } finally {
            lock.readLock().unlock();
        }

        if (autoFlush) {
            initiateFlush(true);
        }
    }

    private static long sizeOf(final Entry<MemorySegment> entry) {
        if (entry == null) {
            return 0L;
        }

        if (entry.value() == null) {
            return entry.key().byteSize();
        }

        return entry.key().byteSize() + entry.value().byteSize();
    }

    private void initiateFlush(final boolean auto) {
        flusher.submit(() -> {
            final TableSet currentTableSet;
            lock.writeLock().lock();
            try {
                if (this.tableSet.memTable.isEmpty()) {
                    // Nothing to flush
                    return;
                }

                if (auto && this.tableSet.memTableSize.get() < config.flushThresholdBytes()) {
                    // Not enough data to flush
                    return;
                }

                // Switch memTable to flushing
                currentTableSet = this.tableSet.flushing();
                this.tableSet = currentTableSet;
            } finally {
                lock.writeLock().unlock();
            }

            // Write
            final int sequence = currentTableSet.nextSequence();
            try {
                new SSTableWriter()
                        .write(
                                config.basePath(),
                                sequence,
                                currentTableSet.flushingTable.get(null, null));
            } catch (IOException e) {
                e.printStackTrace();
                Runtime.getRuntime().halt(-1);
                return;
            }

            // Open
            final SSTable flushed;
            try {
                flushed = SSTables.open(
                        arena,
                        config.basePath(),
                        sequence);
            } catch (IOException e) {
                e.printStackTrace();
                Runtime.getRuntime().halt(-2);
                return;
            }

            // Switch
            lock.writeLock().lock();
            try {
                this.tableSet = this.tableSet.flushed(flushed);
            } finally {
                lock.writeLock().unlock();
            }
        }).state();
    }

    @Override
    public void flush() throws IOException {
        initiateFlush(false);
    }

    @Override
    public void compact() throws IOException {
        compactor.submit(() -> {
            final TableSet currentTableSet;
            lock.writeLock().lock();
            try {
                currentTableSet = this.tableSet;
                if (currentTableSet.ssTables.size() < 2) {
                    // Nothing to compact
                    return;
                }
            } finally {
                lock.writeLock().unlock();
            }

            // Compact to 0
            try {
                new SSTableWriter()
                        .write(
                                config.basePath(),
                                0,
                                new LiveFilteringIterator(
                                        currentTableSet.allSSTableEntries()));
            } catch (IOException e) {
                e.printStackTrace();
                Runtime.getRuntime().halt(-3);
            }

            // Open 0
            final SSTable compacted;
            try {
                compacted =
                        SSTables.open(
                                arena,
                                config.basePath(),
                                0);
            } catch (IOException e) {
                e.printStackTrace();
                Runtime.getRuntime().halt(-4);
                return;
            }

            // Replace old SSTables with compacted one to
            // keep serving requests
            final Set<SSTable> replaced = new HashSet<>(currentTableSet.ssTables);
            lock.writeLock().lock();
            try {
                this.tableSet =
                        this.tableSet.compacted(
                                replaced,
                                compacted);
            } finally {
                lock.writeLock().unlock();
            }

            // Remove compacted SSTables starting from the oldest ones.
            // If we crash, 0 contains all the data, and
            // it will be promoted on reopen.
            for (final SSTable ssTable : currentTableSet.ssTables.reversed()) {
                try {
                    SSTables.remove(
                            config.basePath(),
                            ssTable.sequence);
                } catch (IOException e) {
                    e.printStackTrace();
                    Runtime.getRuntime().halt(-5);
                }
            }

            // Promote zero to one (possibly replacing)
            try {
                SSTables.promote(
                        config.basePath(),
                        0,
                        1);
            } catch (IOException e) {
                e.printStackTrace();
                Runtime.getRuntime().halt(-6);
            }

            // Replace promoted SSTable
            lock.writeLock().lock();
            try {
                this.tableSet =
                        this.tableSet.compacted(
                                Collections.singleton(compacted),
                                compacted.withSequence(1));
            } finally {
                lock.writeLock().unlock();
            }
        }).state();
    }

    @Override
    public void close() throws IOException {
        if (closed.getAndSet(true)) {
            // Already closed
            return;
        }

        // Maybe flush
        flush();

        // Stop all the threads
        flusher.close();
        compactor.close();

        // Close arena
        arena.close();
    }
}
