package ru.vk.itmo.test.smirnovdmitrii.dao.transaction;

import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.ReentrantUpgradableReadWriteLock;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.UpgradableReadWriteLock;

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class LockTransaction<T, E extends Entry<T>> implements Transaction<T, E> {
    private static final AtomicLong transactionN = new AtomicLong();
    private final long revision = transactionN.getAndIncrement();
    private final Map<T, E> cache = new HashMap<>();

    private final Map<T, UpgradableReadWriteLock> localReadLocks = new HashMap<>();
    private final Map<T, UpgradableReadWriteLock> localWriteLocks = new HashMap<>();
    private final Dao<T, E> dao;
    private final TransactionGroup<T> group;
    private final AtomicBoolean released = new AtomicBoolean(false);

    public LockTransaction(
            final Dao<T, E> dao,
            final TransactionGroup<T> group
    ) {
        this.dao = dao;
        this.group = group;
    }

    @Override
    public E get(final T key) {
        Objects.requireNonNull(key);
        final E cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        final UpgradableReadWriteLock lock = group.sharedMap.computeIfAbsent(
                key, k -> new ReentrantUpgradableReadWriteLock()
        );
        if (!lock.tryReadLock()) {
            release();
            throw new ConcurrentModificationException(
                    "while getting " + key + " in transaction " + revision);
        }
        localReadLocks.put(key, lock);
        return dao.get(key);
    }

    @Override
    public void upsert(final E e) {
        Objects.requireNonNull(e);
        final T key = e.key();
        final E cached = cache.put(key, e);
        if (cached != null) {
            return;
        }
        final UpgradableReadWriteLock lock = group.sharedMap.computeIfAbsent(
                key, k -> new ReentrantUpgradableReadWriteLock()
        );
        if (!lock.tryWriteLock()) {
            release();
            throw new ConcurrentModificationException(
                    "while upserting " + e + " in transaction " + revision);
        }
        localWriteLocks.put(key, lock);
    }

    @Override
    public void commit() {
        if (!released.compareAndSet(false, true)) {
            throw new IllegalStateException("Transaction " + revision + " is already released.");
        }
        for (final Map.Entry<T, UpgradableReadWriteLock> entry: localWriteLocks.entrySet()) {
            dao.upsert(cache.get(entry.getKey()));
        }
        release();
    }

    private void release() {
        released.set(true);
        for (final UpgradableReadWriteLock lock: localReadLocks.values()) {
            lock.readUnlock();
        }
        for (final UpgradableReadWriteLock lock: localWriteLocks.values()) {
            lock.writeUnlock();
        }
        cache.clear();
        localWriteLocks.clear();
        localReadLocks.clear();
    }

}
