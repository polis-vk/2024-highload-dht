package ru.vk.itmo.test.chebotinalexandr.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class SkipTombstoneIterator implements Iterator<Entry<MemorySegment>> {
    private final PeekingIterator<Entry<MemorySegment>> iterator;

    public SkipTombstoneIterator(PeekingIterator<Entry<MemorySegment>> iterator) {
        this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
        while (iterator.hasNext() && iterator.peek().value() == null) {
                iterator.next();
            }

        return iterator.hasNext();
    }

    @Override
    public Entry<MemorySegment> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return iterator.next();
    }
}
