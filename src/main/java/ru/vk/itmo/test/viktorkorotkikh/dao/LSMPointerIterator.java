package ru.vk.itmo.test.viktorkorotkikh.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

public abstract class LSMPointerIterator implements Iterator<TimestampedEntry<MemorySegment>> {

    public abstract int getPriority();

    protected abstract MemorySegment getPointerKeySrc();

    protected abstract long getPointerKeySrcOffset();

    protected abstract long getPointerKeySrcSize();

    public abstract boolean isPointerOnTombstone();

    public abstract void shift();

    public abstract long getPointerSize();

    public int compareByPointers(LSMPointerIterator otherIterator) {
        return MemorySegmentComparator.INSTANCE.compare(
                getPointerKeySrc(),
                getPointerKeySrcOffset(),
                getPointerKeySrcOffset() + getPointerKeySrcSize(),
                otherIterator.getPointerKeySrc(),
                otherIterator.getPointerKeySrcOffset(),
                otherIterator.getPointerKeySrcOffset() + otherIterator.getPointerKeySrcSize()
        );
    }

    public int compareByPointersWithPriority(LSMPointerIterator otherIterator) {
        int keyComparison = compareByPointers(otherIterator);
        if (keyComparison == 0) {
            return Integer.compare(otherIterator.getPriority(), getPriority());
        }
        return keyComparison;
    }

    public static final class Empty extends LSMPointerIterator {
        private final int priority;

        public Empty(int priority) {
            this.priority = priority;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        protected MemorySegment getPointerKeySrc() {
            return null;
        }

        @Override
        protected long getPointerKeySrcOffset() {
            return -1;
        }

        @Override
        protected long getPointerKeySrcSize() {
            return -1;
        }

        @Override
        public boolean isPointerOnTombstone() {
            return false;
        }

        @Override
        public void shift() {
            throw new NoSuchElementException();
        }

        @Override
        public long getPointerSize() {
            return -1;
        }

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public TimestampedEntry<MemorySegment> next() {
            throw new NoSuchElementException();
        }
    }
}
