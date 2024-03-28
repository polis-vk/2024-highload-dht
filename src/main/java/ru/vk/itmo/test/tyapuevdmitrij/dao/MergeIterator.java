package ru.vk.itmo.test.tyapuevdmitrij.dao;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

public class MergeIterator implements Iterator<Entry<MemorySegment>> {

    private final PriorityQueue<PeekIterator> priorityQueue;
    private final Comparator<Entry<MemorySegment>> comparator;
    PeekIterator tableIterator;

    private static class PeekIterator implements Iterator<Entry<MemorySegment>> {

        public final int id;
        private final Iterator<Entry<MemorySegment>> delegate;
        private Entry<MemorySegment> memorySegmentsEntry;

        private PeekIterator(int id, Iterator<Entry<MemorySegment>> delegate) {
            this.id = id;
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            if (memorySegmentsEntry == null) {
                return delegate.hasNext();
            }
            return true;
        }

        @Override
        public Entry<MemorySegment> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Entry<MemorySegment> peek = peek();
            this.memorySegmentsEntry = null;
            return peek;
        }

        private Entry<MemorySegment> peek() {
            if (memorySegmentsEntry == null) {
                if (!delegate.hasNext()) {
                    return null;
                }
                memorySegmentsEntry = delegate.next();
            }
            return memorySegmentsEntry;
        }
    }

    public MergeIterator(Collection<Iterator<Entry<MemorySegment>>> iterators,
                         Comparator<Entry<MemorySegment>> comparator) {
        this.comparator = comparator;
        Comparator<PeekIterator> peekComp = (o1, o2) -> comparator.compare(o1.peek(), o2.peek());
        priorityQueue = new PriorityQueue<>(
                iterators.size(),
                peekComp.thenComparing(o -> -o.id)
        );

        int id = 0;
        for (Iterator<Entry<MemorySegment>> iterator : iterators) {
            if (iterator.hasNext()) {
                priorityQueue.add(new PeekIterator(id++, iterator));
            }
        }
    }

    private PeekIterator peek() {
        while (tableIterator == null) {
            tableIterator = priorityQueue.poll();
            if (tableIterator == null) {
                return null;
            }
            peekFromPriorityQueue();
            if (tableIterator.peek() == null) {
                tableIterator = null;
            }
        }

        return tableIterator;
    }

    private void peekFromPriorityQueue() {
        while (true) {
            PeekIterator next = priorityQueue.peek();
            if (next == null) {
                break;
            }
            int compare = comparator.compare(tableIterator.peek(), next.peek());
            if (compare == 0) {
                PeekIterator poll = priorityQueue.poll();
                if (poll != null) {
                    poll.next();
                    if (poll.hasNext()) {
                        priorityQueue.add(poll);
                    }
                }
            } else {
                break;
            }
        }
    }

    @Override
    public boolean hasNext() {
        return peek() != null;
    }

    @Override
    public Entry<MemorySegment> next() {
        PeekIterator entryIterator = peek();
        if (entryIterator == null) {
            throw new NoSuchElementException();
        }
        Entry<MemorySegment> next = entryIterator.next();
        this.tableIterator = null;
        if (entryIterator.hasNext()) {
            priorityQueue.add(entryIterator);
        }
        return next;
    }
}
