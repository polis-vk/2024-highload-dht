package ru.vk.itmo.test.shishiginstepan.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

public class MergeIterator implements Iterator<EntryWithTimestamp<MemorySegment>> {
    private static final Comparator<MemorySegment> keyComparator = (o1, o2) -> {
        long mismatch = o1.mismatch(o2);
        if (mismatch == -1) {
            return 0;
        }
        if (mismatch == o1.byteSize()) {
            return -1;
        }
        if (mismatch == o2.byteSize()) {
            return 1;
        }
        byte b1 = o1.get(ValueLayout.JAVA_BYTE, mismatch);
        byte b2 = o2.get(ValueLayout.JAVA_BYTE, mismatch);
        return Byte.compare(b1, b2);
    };

    private final PriorityQueue<PeekIteratorWrapper> iterators = new PriorityQueue<>((o1, o2) -> {
        int keysCompared = keyComparator.compare(o1.peek().key(), o2.peek().key());
        if (keysCompared == 0) return Integer.compare(o1.priority, o2.priority);
        return keysCompared;
    });

    private static class PeekIteratorWrapper implements Iterator<Entry<MemorySegment>> {
        private EntryWithTimestamp<MemorySegment> prefetched;
        private final Iterator<EntryWithTimestamp<MemorySegment>> iterator;

        private final int priority;

        public PeekIteratorWrapper(Iterator<EntryWithTimestamp<MemorySegment>> iterator, int priority) {
            this.iterator = iterator;
            this.priority = priority;
        }

        @Override
        public boolean hasNext() {
            return this.iterator.hasNext() || this.prefetched != null;
        }

        @Override
        public EntryWithTimestamp<MemorySegment> next() {
            if (this.prefetched == null) {
                return this.iterator.next();
            } else {
                EntryWithTimestamp<MemorySegment> toReturn = this.prefetched;
                this.prefetched = null;
                return toReturn;
            }
        }

        public Entry<MemorySegment> peek() {
            if (this.prefetched == null) {
                this.prefetched = this.iterator.next();
            }
            return this.prefetched;
        }

        public void skip() {
            if (this.prefetched != null) {
                this.prefetched = null;
                return;
            }
            this.iterator.next();
        }
    }

    public MergeIterator(List<Iterator<EntryWithTimestamp<MemorySegment>>> iterators) {
        // приоритет мержа будет определен порядком итераторов
        for (int i = 0; i < iterators.size(); i++) {
            Iterator<EntryWithTimestamp<MemorySegment>> iterator = iterators.get(i);
            if (iterator.hasNext()) {
                this.iterators.add(new PeekIteratorWrapper(iterator, i));
            }
        }
    }

    @Override
    public boolean hasNext() {
        PeekIteratorWrapper nextIterator = iterators.peek();
        return nextIterator != null;
    }

    @Override
    public EntryWithTimestamp<MemorySegment> next() {
        PeekIteratorWrapper nextIterator = iterators.remove();
        EntryWithTimestamp<MemorySegment> nextEntry = nextIterator.next();
        if (nextIterator.hasNext()) {
            this.iterators.add(nextIterator);
        }
        skipOverrides(nextEntry);
        return nextEntry;
    }

    private void skipOverrides(Entry<MemorySegment> entry) {
        while (hasNext()) {
            PeekIteratorWrapper nextIterator = this.iterators.peek();
            if (nextIterator == null) break;
            Entry<MemorySegment> nextEntry = nextIterator.peek();
            if (entry.key().mismatch(nextEntry.key()) == -1) {
                nextIterator.skip();
                this.iterators.remove();
                if (nextIterator.hasNext()) this.iterators.add(nextIterator);
            } else break;
        }

    }
}
