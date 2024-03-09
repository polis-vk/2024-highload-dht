package ru.vk.itmo.test.kovalevigor.dao;

import java.util.Iterator;

public class PriorityShiftedIterator<E> extends ShiftedIterator<E> implements Comparable<PriorityShiftedIterator<E>> {

    private final int priority;

    public PriorityShiftedIterator(final int priority) {
        super();
        this.priority = priority;
    }

    public PriorityShiftedIterator(final int priority, final Iterator<E> iterator) {
        super(iterator);
        this.priority = priority;
    }

    @Override
    public int compareTo(final PriorityShiftedIterator rhs) {
        return Integer.compare(priority, rhs.priority);
    }
}
