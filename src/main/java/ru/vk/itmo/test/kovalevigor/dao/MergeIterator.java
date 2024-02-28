package ru.vk.itmo.test.kovalevigor.dao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

public abstract class MergeIterator<E, T extends Iterator<E>> implements Iterator<E> {

    protected final PriorityQueue<T> queue;

    protected MergeIterator(final Collection<? extends T> collection) {
        final List<T> filteredCollection = new ArrayList<>(collection.size());
        for (final T iterator : collection) {
            if (iterator.hasNext()) {
                filteredCollection.add(iterator);
            }
        }
        queue = new PriorityQueue<>(filteredCollection);
    }

    @Override
    public boolean hasNext() {
        return !queue.isEmpty();
    }

    @Override
    public E next() {
        final T iterator = queue.remove();

        T nextIterator = queue.peek();
        while (nextIterator != null && checkEquals(iterator, nextIterator)) {
            shiftAdd(queue.remove());
            nextIterator = queue.peek();
        }
        return shiftAdd(iterator);
    }

    protected abstract boolean checkEquals(T lhs, T rhs);

    private E shiftAdd(final T iterator) {
        final E value = iterator.next();
        if (iterator.hasNext()) {
            queue.add(iterator);
        }
        return value;
    }
}
