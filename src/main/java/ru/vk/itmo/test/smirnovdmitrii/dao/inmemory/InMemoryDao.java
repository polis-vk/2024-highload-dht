package ru.vk.itmo.test.smirnovdmitrii.dao.inmemory;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.smirnovdmitrii.dao.state.State;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public interface InMemoryDao<D, E extends Entry<D>> extends AutoCloseable {

    /**
     * Maybe flushes memtable to file. Works in background.
     */
    void flush() throws IOException;

    /**
     * Return iterators for every in memory storage sorted from newer to older from key {@code from} to key {@code to}.
     * @param from from key.
     * @param to to key.
     * @return returned iterator.
     */
    List<Iterator<E>> get(State state, D from, D to);

    /**
     * Return entry that associated with key {@code key}. Null if there is no entry with such key.
     * @param key key to search.
     * @return entry associated with key.
     */
    E get(State state, D key);

    /**
     * Adding entry to in memory dao. If there was entry with same key, then replace it.
     * @param entry entry to add.
     */
    boolean upsert(State state, E entry) throws IOException;

    @Override
    void close();
}
