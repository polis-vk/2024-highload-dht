package ru.vk.itmo.test.smirnovdmitrii.dao.util.iterators;

import java.util.Iterator;

public interface PeekingIterator<T> extends Iterator<T> {

    /**
     * returns next element of iterator without removing it.
     * @return next element of iterator
     */
    T peek();

    /**
     * returns id of current iterator.
     * @return id.
     */
    int getId();
}
