package ru.vk.itmo.test.chebotinalexandr.dao;

import java.util.Iterator;

public class PeekingIteratorImpl<E> implements PeekingIterator<E> {
    private final Iterator<E> iterator;
    private E nextElement;
    private final int priority;

    public PeekingIteratorImpl(Iterator<E> iterator) {
        this(iterator, 0);
    }

    public PeekingIteratorImpl(Iterator<E> iterator, int priority) {
        this.iterator = iterator;
        this.priority = priority;

        if (iterator.hasNext()) {
            nextElement = iterator.next();
        }
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public E peek() {
        return nextElement;
    }

    @Override
    public boolean hasNext() {
        return nextElement != null || iterator.hasNext();
    }

    @Override
    public E next() {
        E currElement = nextElement;

        if (iterator.hasNext()) {
            nextElement = iterator.next();
        } else {
            nextElement = null;
        }

        return currElement;
    }
}
