package ru.vk.itmo.test.solnyshkoksenia.dao;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;

public class MergeIterator<T> implements Iterator<T> {
    private final PriorityQueue<PeekIterator<T>> priorityQueue;
    private final Comparator<T> comparator;
    private PeekIterator<T> peek;

    private static class PeekIterator<T> implements Iterator<T> {
        public final int id;
        private final Iterator<T> delegate;
        private T peek;

        private PeekIterator(int id, Iterator<T> delegate) {
            this.id = id;
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            if (peek == null) {
                return delegate.hasNext();
            }
            return true;
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            T newPeek = peek();
            this.peek = null;
            return newPeek;
        }

        private T peek() {
            if (peek == null) {
                if (!delegate.hasNext()) {
                    return null;
                }
                peek = delegate.next();
            }
            return peek;
        }
    }

    public MergeIterator(Collection<Iterator<T>> iterators, Comparator<T> comparator) {
        this.comparator = comparator;
        Comparator<PeekIterator<T>> peekComp = (o1, o2) -> comparator.compare(o1.peek(), o2.peek());
        priorityQueue = new PriorityQueue<>(
                iterators.size(),
                peekComp.thenComparing(o -> -o.id)
        );

        int id = 0;
        for (Iterator<T> iterator : iterators) {
            if (iterator.hasNext()) {
                priorityQueue.add(new PeekIterator<>(id++, iterator));
            }
        }
    }

    private PeekIterator<T> peek() {
        return peek == null ? findPeek() : peek;
    }

    private PeekIterator<T> findPeek() {
        while (peek == null) {
            if (priorityQueue.isEmpty()) {
                return null;
            }

            peek = priorityQueue.poll();

            while (!priorityQueue.isEmpty()) {
                PeekIterator<T> next = priorityQueue.peek();
                if (comparator.compare(peek.peek(), next.peek()) != 0) {
                    break;
                }
                skip(Objects.requireNonNull(priorityQueue.poll()));
            }

            if (skip(peek.peek())) {
                skip(peek);
                peek = null;
            }
        }
        return peek;
    }

    protected boolean skip(T t) {
        return t == null;
    }

    private void skip(PeekIterator<T> iterator) {
        iterator.next();
        if (iterator.hasNext()) {
            priorityQueue.add(iterator);
        }
    }

    @Override
    public boolean hasNext() {
        return peek() != null;
    }

    @Override
    public T next() {
        PeekIterator<T> peekIt = peek();
        if (peekIt == null) {
            throw new NoSuchElementException();
        }
        T next = peekIt.next();
        this.peek = null;
        if (peekIt.hasNext()) {
            priorityQueue.add(peekIt);
        }
        return next;
    }
}
