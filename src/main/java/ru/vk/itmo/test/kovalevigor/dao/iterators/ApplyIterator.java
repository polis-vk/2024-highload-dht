package ru.vk.itmo.test.kovalevigor.dao.iterators;

import java.util.Iterator;
import java.util.function.Function;

public class ApplyIterator<E, T> implements Iterator<T> {

    private final Iterator<E> iterator;
    private final Function<E, T> conv;

    public ApplyIterator() {
        this.iterator = null;
        this.conv = null;
    }

    public ApplyIterator(final Iterator<E> iterator, final Function<E, T> conv) {
        this.iterator = iterator;
        this.conv = conv;
    }

    public ApplyIterator(final Iterator<E> iterator) {
        this(iterator, e -> null);
    }

    @Override
    public boolean hasNext() {
        return iterator != null && iterator.hasNext();
    }

    @Override
    public T next() {
        return conv.apply(iterator.next());
    }
}
