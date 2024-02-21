package ru.vk.itmo.test.ryabovvadim.iterators;

import java.util.Collection;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

public class GatheringIterator<T> implements FutureIterator<T> {
    private final FutureIterator<T> delegate;

    public <I extends FutureIterator<T>> GatheringIterator(
            Collection<? extends I> iterators,
            Comparator<? super I> iteratorsComparator,
            Comparator<? super T> valuesComparator
    ) {
        PriorityQueue<I> heap = new PriorityQueue<>(iteratorsComparator);
        heap.addAll(iterators);

        this.delegate = new LazyIterator<>(() -> nextImpl(heap, valuesComparator), () -> !heap.isEmpty());
    }

    @Override
    public T showNext() {
        return delegate.showNext();
    }

    @Override
    public boolean hasNext() {
        return delegate.hasNext();
    }

    @Override
    public T next() {
        return delegate.next();
    }

    private <I extends FutureIterator<T>> T nextImpl(
            PriorityQueue<I> heap,
            Comparator<? super T> valuesComparator
    ) {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        I it = heap.poll();
        T res = it.next();
        if (it.hasNext()) {
            heap.add(it);
        }

        while (hasNext()) {
            it = heap.peek();
            T curVal = it.showNext();
            int compareResult = valuesComparator.compare(res, curVal);

            if (compareResult == 0) {
                heap.poll();
                it.next();
                if (it.hasNext()) {
                    heap.add(it);
                }
            } else {
                break;
            }
        }

        return res;
    }
}
