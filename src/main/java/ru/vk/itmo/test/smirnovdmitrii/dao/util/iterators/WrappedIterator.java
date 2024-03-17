package ru.vk.itmo.test.smirnovdmitrii.dao.util.iterators;

import java.util.Iterator;

public class WrappedIterator<T> implements PeekingIterator<T> {
   private final int id;
   private final Iterator<T> iterator;
   private T next;

    public WrappedIterator(final int id, final Iterator<T> iterator) {
        this.id = id;
        this.iterator = iterator;
    }

    @Override
    public T peek() {
        if (next == null) {
            next = iterator.next();
        }
        return next;
    }

    @Override
    public boolean hasNext() {
        return next != null || iterator.hasNext();
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public T next() {
        if (next == null) {
            return iterator.next();
        }
        final T result = next;
        next = null;
        return result;
    }
}
