package ru.vk.itmo.test.nikitaprokopev.dao;

public interface Dao<D, E extends Entry<D>> extends java.io.Closeable {
    /**
     * Returns ordered iterator of entries with keys between from (inclusive) and to (exclusive).
     * @param from lower bound of range (inclusive)
     * @param to upper bound of range (exclusive)
     * @return entries [from;to)
     */
    java.util.Iterator<E> get(D from, D to);

    /**
     * Returns entry by key. Note: default implementation is far from optimal.
     * @param key entry`s key
     * @return entry
     */
    default E get(D key) {
        java.util.Iterator<E> iterator = get(key, null);
        if (!iterator.hasNext()) {
            return null;
        }

        E next = iterator.next();
        if (next.key().equals(key)) {
            return next;
        }
        return null;
    }

    /**
     * Returns ordered iterator of all entries with keys from (inclusive).
     * @param from lower bound of range (inclusive)
     * @return entries with key >= from
     */
    default java.util.Iterator<E> allFrom(D from) {
        return get(from, null);
    }

    /**
     * Returns ordered iterator of all entries with keys < to.
     * @param to upper bound of range (exclusive)
     * @return entries with key < to
     */
    default java.util.Iterator<E> allTo(D to) {
        return get(null, to);
    }

    /**
     * Returns ordered iterator of all entries.
     * @return all entries
     */
    default java.util.Iterator<E> all() {
        return get(null, null);
    }

    /**
     * Inserts of replaces entry.
     * @param entry element to upsert
     */
    void upsert(E entry);

    /**
     * Persists data (no-op by default).
     */
    default void flush() throws java.io.IOException {
        // Do nothing
    }

    /**
     * Compacts data (no-op by default).
     */
    default void compact() throws java.io.IOException {
        // Do nothing
    }

    /*
     * Releases Dao (calls flush by default).
     */
    @Override
    default void close() throws java.io.IOException {
        flush();
    }
}
