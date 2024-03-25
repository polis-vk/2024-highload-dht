package ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.smirnovdmitrii.dao.TimeEntry;
import ru.vk.itmo.test.smirnovdmitrii.dao.state.State;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public interface OutMemoryDao<D, E extends Entry<D>> extends AutoCloseable {

    /**
     * Returns value from disk storage. If value is not found then return null
     * @param key key for searching value.
     * @return founded value.
     */
    E get(State state, D key);

    /**
     * Within this method you can save your in memory storage ({@link Iterable}) on disk.
     * @param entries representing memtable.
     */
    void flush(Iterable<E> entries) throws IOException;

    /**
     * Returs iterator for every sstable, that was flushed in order from more new to more old.
     * @return list of sstable iterators.
     */
    List<Iterator<E>> get(State state, D from, D to);

    /**
     * Compact all sstables on disk in one sstable. Works in background.
     */
    void compact();

    @Override
    void close() throws IOException;

}
