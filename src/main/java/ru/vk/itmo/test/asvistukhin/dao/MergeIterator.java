package ru.vk.itmo.test.asvistukhin.dao;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

public class MergeIterator<T> implements Iterator<T> {

    private final PriorityQueue<PeekIterator<T>> priorityQueue;
    private final Comparator<T> comparator;
    private PeekIterator<T> nextIterator;

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
            T peeked = peek();
            this.peek = null;
            return peeked;
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
        while (nextIterator == null) {
            nextIterator = priorityQueue.poll();
            if (nextIterator == null) {
                return null;
            }

            skipIteratorsWithSameKey();

            if (nextIterator.peek() == null) {
                nextIterator = null;
                continue;
            }

            if (shouldSkip(nextIterator.peek())) {
                moveNextAndPutBack(nextIterator);
                nextIterator = null;
            }
        }

        return nextIterator;
    }

    private void skipIteratorsWithSameKey() {
        while (true) {
            PeekIterator<T> next = priorityQueue.peek();
            if (next == null) {
                break;
            }

            if (!skipTheSameKey(next)) {
                break;
            }
        }
    }

    private boolean skipTheSameKey(PeekIterator<T> next) {
        int compare = comparator.compare(nextIterator.peek(), next.peek());
        if (compare != 0) {
            return false;
        }

        PeekIterator<T> poll = priorityQueue.poll();
        if (poll != null) {
            moveNextAndPutBack(poll);
        }
        return true;
    }

    private void moveNextAndPutBack(PeekIterator<T> poll) {
        poll.next();
        if (poll.hasNext()) {
            priorityQueue.add(poll);
        }
    }

    protected boolean shouldSkip(T t) {
        return t == null;
    }

    @Override
    public boolean hasNext() {
        return peek() != null;
    }

    @Override
    public T next() {
        PeekIterator<T> peeked = peek();
        if (peeked == null) {
            throw new NoSuchElementException();
        }
        T nextValue = peeked.next();
        this.nextIterator = null;
        if (peeked.hasNext()) {
            priorityQueue.add(peeked);
        }
        return nextValue;
    }
}
