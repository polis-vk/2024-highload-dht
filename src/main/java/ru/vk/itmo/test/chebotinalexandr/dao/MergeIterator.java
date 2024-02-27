package ru.vk.itmo.test.chebotinalexandr.dao;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * See also <a href="https://github.com/google/guava/blob/9b8035d654cd4e36e79e1b8329f4457e0f3087f8/guava/src/com/google/common/collect/Iterators.java#L1315">Merging iterator from guava</a>.
 */
public class MergeIterator<E> implements Iterator<E> {
    private final Queue<PeekingIterator<E>> queue;
    private final Comparator<? super E> comparator;

    public MergeIterator(Queue<PeekingIterator<E>> queue, Comparator<? super E> comparator) {
        this.queue = queue;
        this.comparator = comparator;
    }

    public static <E> Iterator<E> merge(List<PeekingIterator<E>> iterators, Comparator<? super E> comparator) {
        if (iterators.isEmpty()) {
            return Collections.emptyIterator();
        }

        if (iterators.size() == 1) {
            return iterators.getFirst();
        }

        Queue<PeekingIterator<E>> queue = getPeekingIterators(iterators.size(), comparator);

        for (PeekingIterator<E> it : iterators) {
            if (it.hasNext()) {
                queue.add(it);
            }
        }

        return new MergeIterator<>(queue, comparator);
    }

    private static <E> Queue<PeekingIterator<E>> getPeekingIterators(final int size, Comparator<? super E> comparator) {
        Comparator<PeekingIterator<E>> heapComparator =
                (PeekingIterator<E> o1, PeekingIterator<E> o2) -> {
                    int compare = comparator.compare(o1.peek(), o2.peek());

                    if (compare == 0) {
                        return o1.priority() < o2.priority() ? -1 : 1;
                    }

                    return compare;
                };

        return new PriorityQueue<>(size, heapComparator);
    }

    @Override
    public boolean hasNext() {
        return !queue.isEmpty();
    }

    @Override
    public E next() {
        PeekingIterator<E> iterator = queue.remove();
        PeekingIterator<E> poll = queue.poll();

        if (poll == null) {
            iterator.peek();
        }

        while (poll != null) {
            E key = iterator.peek();
            E findKey = poll.peek();
            if (comparator.compare(key, findKey) != 0) {
                break;
            }

            poll.next();
            if (poll.hasNext()) {
                queue.offer(poll);
            }

            poll = queue.poll();
        }

        if (poll != null) {
            queue.offer(poll);
        }

        E next = iterator.next();

        if (iterator.hasNext()) {
            queue.offer(iterator);
        }

        return next;
    }
}
