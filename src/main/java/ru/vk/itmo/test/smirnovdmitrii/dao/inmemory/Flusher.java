package ru.vk.itmo.test.smirnovdmitrii.dao.inmemory;

import ru.vk.itmo.test.smirnovdmitrii.dao.TimeEntry;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.OutMemoryDao;
import ru.vk.itmo.test.smirnovdmitrii.dao.state.StateService;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class Flusher implements Closeable {

    private final StateService stateService;
    private final OutMemoryDao<MemorySegment, TimeEntry<MemorySegment>> outMemoryDao;
    private final Supplier<Memtable> memtableSupplier;
    private final AtomicBoolean isFlushing = new AtomicBoolean(false);
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public Flusher(
            final StateService stateService,
            final OutMemoryDao<MemorySegment, TimeEntry<MemorySegment>> outMemoryDao,
            final Supplier<Memtable> memtableSupplier
    ) {
        this.stateService = stateService;
        this.outMemoryDao = outMemoryDao;
        this.memtableSupplier = memtableSupplier;

        stateService.setMemtables(List.of(memtableSupplier.get()));
    }

    public boolean flush() {
        if (isFlushing.compareAndSet(false, true)) {
            forceFlush();
            return true;
        }
        return false;
    }

    public void forceFlush() {
        executorService.execute(() -> {
            try {
                final List<Memtable> memtables = stateService.memtables();
                final Memtable memtable = memtables.getFirst();
                final Memtable newMemtable = memtableSupplier.get();
                // Creating new memory table.
                stateService.addMemtable(newMemtable);
                // Waiting until all upserts finished and flushing it to disk.
                // need a structure only to wait until everyone is has gone
                memtable.flushLock().lock();
                try {
                    outMemoryDao.flush(memtable);
                    stateService.removeMemtable(memtable);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    memtable.flushLock().unlock();
                }
            } finally {
                isFlushing.set(false);
            }
        });
    }

    @Override
    public void close() {
        forceFlush();
        executorService.close();
        stateService.setMemtables(null);
    }
}
