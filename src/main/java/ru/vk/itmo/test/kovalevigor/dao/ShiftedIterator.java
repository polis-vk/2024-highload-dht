package ru.vk.itmo.test.kovalevigor.dao;

import java.util.Iterator;

public class ShiftedIterator<E> implements Iterator<E> {

    private final Iterator<E> iterator;
    protected E value;
    private boolean finished;

    public ShiftedIterator() {
        this.iterator = null;
        this.value = null;
        this.finished = true;
    }

    public ShiftedIterator(final Iterator<E> iterator) {
        this.iterator = iterator;
        this.value = this.iterator.hasNext() ? this.iterator.next() : null;
        this.finished = this.value == null;
    }

    @Override
    public boolean hasNext() {
        return !finished;
    }

    @Override
    public E next() {
        final E result = value;
        if (iterator.hasNext()) {
            value = iterator.next();
        } else {
            finished = true;
        }
        return result;
    }
}
