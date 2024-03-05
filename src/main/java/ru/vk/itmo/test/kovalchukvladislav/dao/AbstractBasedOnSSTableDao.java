package ru.vk.itmo.test.kovalchukvladislav.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.DaoIterator;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.EntryExtractor;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.SimpleDaoLoggerUtility;
import ru.vk.itmo.test.kovalchukvladislav.dao.storage.InMemoryStorage;
import ru.vk.itmo.test.kovalchukvladislav.dao.storage.InMemoryStorageImpl;
import ru.vk.itmo.test.kovalchukvladislav.dao.storage.SSTableStorage;
import ru.vk.itmo.test.kovalchukvladislav.dao.storage.SSTableStorageImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public abstract class AbstractBasedOnSSTableDao<D, E extends Entry<D>> implements Dao<D, E> {
    private final Logger logger = SimpleDaoLoggerUtility.createLogger(getClass());
    private static final String DB_FILENAME_PREFIX = "db_";
    private static final String METADATA_FILENAME = "metadata";
    private static final String OFFSETS_FILENAME_PREFIX = "offsets_";

    private final Path basePath;
    private final long flushThresholdBytes;
    private final EntryExtractor<D, E> extractor;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicBoolean isFlushingOrCompacting = new AtomicBoolean(false);
    private final ExecutorService flushOrCompactQueue = Executors.newSingleThreadExecutor();

    /**
     * В get(), upsert() и compact() для inMemoryStorage и ssTableStorage не требуется синхронизация между собой.
     * Исключение составляет только flush() и compact().
     * Следует проследить что на любом этапе оба стораджа в сумме будут иметь полные данные.
     */
    private final InMemoryStorage<D, E> inMemoryStorage;
    private final SSTableStorage<D, E> ssTableStorage;

    protected AbstractBasedOnSSTableDao(Config config, EntryExtractor<D, E> extractor) throws IOException {
        this.extractor = extractor;
        this.flushThresholdBytes = config.flushThresholdBytes();
        this.basePath = Objects.requireNonNull(config.basePath());
        this.inMemoryStorage = new InMemoryStorageImpl<>(extractor, config.flushThresholdBytes());
        this.ssTableStorage = new SSTableStorageImpl<>(basePath, METADATA_FILENAME,
                DB_FILENAME_PREFIX, OFFSETS_FILENAME_PREFIX, extractor);
    }

    @Override
    public Iterator<E> get(D from, D to) {
        List<Iterator<E>> iterators = new ArrayList<>();
        iterators.addAll(inMemoryStorage.getIterators(from, to));
        iterators.addAll(ssTableStorage.getIterators(from, to));
        return new DaoIterator<>(iterators, extractor);
    }

    @Override
    public E get(D key) {
        E e = inMemoryStorage.get(key);
        if (e != null) {
            return e.value() == null ? null : e;
        }
        E fromFile = ssTableStorage.get(key);
        return (fromFile == null || fromFile.value() == null) ? null : fromFile;
    }

    @Override
    public void upsert(E entry) {
        long size = inMemoryStorage.upsertAndGetSize(entry);
        if (size >= flushThresholdBytes) {
            flush();
        }
    }

    @Override
    public void flush() {
        if (!isFlushingOrCompacting.compareAndSet(false, true)) {
            logger.info("Flush or compact already in process");
            return;
        }
        Callable<String> flushCallable = inMemoryStorage.prepareFlush(
                basePath,
                DB_FILENAME_PREFIX,
                OFFSETS_FILENAME_PREFIX);
        if (flushCallable == null) {
            isFlushingOrCompacting.set(false);
            return;
        }
        submitFlushAndAddSSTable(flushCallable);
    }

    private void submitFlushAndAddSSTable(Callable<String> flushCallable) {
        flushOrCompactQueue.execute(() -> {
            try {
                String newTimestamp = flushCallable.call();
                ssTableStorage.addSSTableId(newTimestamp, true);
                inMemoryStorage.completeFlush();
            } catch (Exception e) {
                inMemoryStorage.failFlush();
            } finally {
                isFlushingOrCompacting.set(false);
            }
        });
    }

    @Override
    public void close() {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }

        flushOrCompactQueue.close();
        try {
            String newTimestamp = inMemoryStorage.close(basePath, DB_FILENAME_PREFIX, OFFSETS_FILENAME_PREFIX);
            if (newTimestamp != null) {
                ssTableStorage.addSSTableId(newTimestamp, false);
            }
        } catch (Exception e) {
            logger.severe(() -> "Error while flushing on close: " + e.getMessage());
        }
        ssTableStorage.close();
    }

    @Override
    public void compact() {
        if (!isFlushingOrCompacting.compareAndSet(false, true)) {
            logger.info("Flush or compact already in process");
            return;
        }
        flushOrCompactQueue.execute(() -> {
            try {
                ssTableStorage.compact();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                isFlushingOrCompacting.set(false);
            }
        });
    }
}
