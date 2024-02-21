package ru.vk.itmo.test.kovalchukvladislav.dao.storage;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.EntryExtractor;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.MemoryOverflowException;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.SimpleDaoLoggerUtility;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.TableInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

public class InMemoryStorageImpl<D, E extends Entry<D>> implements InMemoryStorage<D, E> {
    private static final StandardCopyOption[] MOVE_OPTIONS = new StandardCopyOption[]{
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
    };
    private static final Logger logger = SimpleDaoLoggerUtility.createLogger(InMemoryStorageImpl.class);
    private final long flushThresholdBytes;
    private final EntryExtractor<D, E> extractor;

    private volatile DaoState<D, E> daoState;
    private volatile FlushingDaoState<D, E> flushingDaoState;
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();

    public InMemoryStorageImpl(EntryExtractor<D, E> extractor, long flushThresholdBytes) {
        this.extractor = extractor;
        this.flushThresholdBytes = flushThresholdBytes;
        this.daoState = createEmptyDaoState();
        this.flushingDaoState = createEmptyFlushingDaoState();

    }

    private DaoState<D, E> createEmptyDaoState() {
        return new DaoState<>(new ConcurrentSkipListMap<>(extractor), new AtomicLong(0));
    }

    private FlushingDaoState<D, E> createEmptyFlushingDaoState() {
        return new FlushingDaoState<>(new ConcurrentSkipListMap<>(extractor), 0, FlushingState.NOT_RUNNING);
    }

    /**
     * daoSize допустимо увеличивать внутри readLock для большей производительности.
     * Например, в upsert надо атомарно увеличить или уменьшить значение, а не узнать корректное.
     * В операциях на чтение точного значения daoSize (flush) следует использовать writeLock.
     * writeLock() гарантирует что в настоящее время нет readLock'ов, а значит и незаконченных операций изменения size.
     */
    @SuppressWarnings("unused")
    private record DaoState<D, E extends Entry<D>>(ConcurrentNavigableMap<D, E> dao, AtomicLong daoSize) {
        public FlushingDaoState<D, E> toFlushingRunningDaoState() {
            return new FlushingDaoState<>(dao, daoSize.get(), FlushingState.RUNNING);
        }
    }

    @SuppressWarnings("unused")
    private record FlushingDaoState<D, E>(ConcurrentNavigableMap<D, E> dao, long daoSize, FlushingState flushingState) {

        public FlushingDaoState<D, E> toFailed() {
            return new FlushingDaoState<>(dao, daoSize, FlushingState.FAILED);
        }

        public FlushingDaoState<D, E> failedToTryAgain() {
            if (flushingState != FlushingState.FAILED) {
                throw new IllegalStateException("This method should be called when state is failed");
            }
            return new FlushingDaoState<>(dao, daoSize, FlushingState.RUNNING);
        }
    }

    private enum FlushingState {
        NOT_RUNNING,
        RUNNING,
        FAILED,
        // Можно добавить еще одно состояние: данные выгружены, но произошло исключение при их релоаде в SSTableStorage.
        // Позволит при повторном flush вернуть уже готовый timestamp, а не флашить опять в новый файл.
    }

    @Override
    public E get(D key) {
        ConcurrentNavigableMap<D, E> dao;
        ConcurrentNavigableMap<D, E> flushingDao;

        stateLock.readLock().lock();
        try {
            dao = daoState.dao;
            flushingDao = flushingDaoState.dao;
        } finally {
            stateLock.readLock().unlock();
        }

        E entry = dao.get(key);
        if (entry == null && !flushingDao.isEmpty()) {
            entry = flushingDao.get(key);
        }
        return entry;
    }

    /**
     * Возвращает не точное значение size в угоду перфомансу, иначе будет куча writeLock.
     * При параллельных upsert(), в одном из них daoSize может не успеть инкрементироваться для вставляемого entry.
     * Тем не менее "приблизительное size" можно использовать для детекта случаев когда надо делать flush().
     */
    @Override
    public long upsertAndGetSize(E entry) {
        stateLock.readLock().lock();
        try {
            // "приблизительный" size, не пользуемся write lock'ами в угоду перфомансу
            AtomicLong daoSize = getDaoSizeOrThrowMemoryOverflow(flushThresholdBytes, daoState, flushingDaoState);

            E oldEntry = daoState.dao.put(entry.key(), entry);
            long delta = extractor.size(entry) - extractor.size(oldEntry);
            return daoSize.addAndGet(delta);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private static <D, E extends Entry<D>> AtomicLong getDaoSizeOrThrowMemoryOverflow(
            long flushThresholdBytes, DaoState<D, E> daoState, FlushingDaoState<D, E> flushingDaoState) {
        AtomicLong daoSize = daoState.daoSize;
        if (daoSize.get() < flushThresholdBytes) {
            return daoSize;
        }
        FlushingState flushingState = flushingDaoState.flushingState();
        if (flushingState == FlushingState.RUNNING) {
            throw new MemoryOverflowException("There no free space."
                    + "daoSize is max, previous flush running and not completed");
        } else if (flushingState == FlushingState.FAILED) {
            throw new MemoryOverflowException("There no free space."
                    + "daoSize is max, previous flush was failed. Try to repeat flush");
        }
        return daoSize;
    }

    @Override
    public List<Iterator<E>> getIterators(D from, D to) {
        ConcurrentNavigableMap<D, E> dao;
        ConcurrentNavigableMap<D, E> flushingDao;

        stateLock.readLock().lock();
        try {
            dao = daoState.dao;
            flushingDao = flushingDaoState.dao;
        } finally {
            stateLock.readLock().unlock();
        }

        List<Iterator<E>> result = new ArrayList<>(2);
        result.add(getIteratorDao(dao, from, to));
        result.add(getIteratorDao(flushingDao, from, to));
        return result;
    }

    private Iterator<E> getIteratorDao(ConcurrentNavigableMap<D, E> dao, D from, D to) {
        ConcurrentNavigableMap<D, E> subMap;
        if (from == null && to == null) {
            subMap = dao;
        } else if (from == null) {
            subMap = dao.headMap(to);
        } else if (to == null) {
            subMap = dao.tailMap(from);
        } else {
            subMap = dao.subMap(from, to);
        }
        return subMap.values().iterator();
    }

    /**
     * Возвращает таску для flush(). Таска возвращает новый таймстемп файлов в base path.
     * Если уже выполняется flush, возвращает null.
     * Если предыдущий flush() был завершен с ошибкой, возвратит таску на повторную попытку.
     * Если нет данных, возвращает null.
     */
    @Override
    public Callable<String> prepareFlush(Path basePath, String dbFilenamePrefix, String offsetsFilenamePrefix) {
        FlushingDaoState<D, E> newFlushingDaoState;

        stateLock.writeLock().lock();
        try {
            switch (flushingDaoState.flushingState) {
                case RUNNING -> {
                    return null;
                }
                case FAILED -> {
                    newFlushingDaoState = flushingDaoState.failedToTryAgain();
                    flushingDaoState = newFlushingDaoState;
                }
                case NOT_RUNNING -> {
                    DaoState<D, E> newDaoState = createEmptyDaoState();
                    newFlushingDaoState = daoState.toFlushingRunningDaoState();

                    flushingDaoState = newFlushingDaoState;
                    daoState = newDaoState;
                }
                default -> throw new IllegalStateException("Unexpected state: " + flushingDaoState.flushingState);
            }
        } finally {
            stateLock.writeLock().unlock();
        }

        return () -> {
            ConcurrentNavigableMap<D, E> dao = newFlushingDaoState.dao;
            int recordsCount = dao.size();
            long daoSize = newFlushingDaoState.daoSize;
            TableInfo tableInfo = new TableInfo(recordsCount, daoSize);

            return flushImpl(dao.values().iterator(), tableInfo, basePath, dbFilenamePrefix, offsetsFilenamePrefix);
        };
    }

    private String flushImpl(Iterator<E> immutableCollectionIterator, TableInfo info, Path basePath,
                             String dbPrefix, String offsetsPrefix) throws IOException {
        String timestamp = String.valueOf(System.currentTimeMillis());
        Path tempDirectory = Files.createTempDirectory(null);
        Path newSSTable = null;

        logger.info(() -> String.format("Flushing started to dir %s, timestamp %s, info %s",
                tempDirectory, timestamp, info));
        try {
            Path tmpSSTable = tempDirectory.resolve(dbPrefix + timestamp);
            Path tmpOffsets = tempDirectory.resolve(offsetsPrefix + timestamp);

            StorageUtility.writeData(tmpSSTable, tmpOffsets, immutableCollectionIterator, info, extractor);

            newSSTable = Files.move(tmpSSTable, basePath.resolve(dbPrefix + timestamp), MOVE_OPTIONS);
            Files.move(tmpOffsets, basePath.resolve(offsetsPrefix + timestamp), MOVE_OPTIONS);
        } catch (Exception e) {
            // newOffsets чистить не надо. Это последняя операция, если исключение то он точно не перемещен.
            if (newSSTable != null) {
                StorageUtility.deleteUnusedFiles(logger, newSSTable);
            }
            throw e;
        } finally {
            StorageUtility.deleteUnusedFilesInDirectory(logger, tempDirectory);
        }
        logger.info(() -> String.format("Flushed to dir %s, timestamp %s", basePath, timestamp));
        return timestamp;
    }

    @Override
    public void failFlush() {
        stateLock.writeLock().lock();
        try {
            // Помечаем как failed, но не чистим мапу и не теряем данные
            flushingDaoState = flushingDaoState.toFailed();
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public void completeFlush() {
        stateLock.writeLock().lock();
        try {
            flushingDaoState = createEmptyFlushingDaoState();
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public String close(Path basePath, String dbFilenamePrefix, String offsetsFilenamePrefix) throws IOException {
        ConcurrentNavigableMap<D, E> dao;
        long size;

        stateLock.writeLock().lock();
        try {
            dao = daoState.dao;
            size = daoState.daoSize.get();
            daoState = null;
        } finally {
            stateLock.writeLock().unlock();
        }

        if (dao.isEmpty()) {
            return null;
        }
        int recordsCount = dao.size();
        TableInfo tableInfo = new TableInfo(recordsCount, size);
        return flushImpl(dao.values().iterator(), tableInfo, basePath, dbFilenamePrefix, offsetsFilenamePrefix);
    }
}
