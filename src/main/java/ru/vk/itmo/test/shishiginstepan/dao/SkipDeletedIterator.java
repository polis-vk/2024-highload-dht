package ru.vk.itmo.test.shishiginstepan.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class SkipDeletedIterator implements Iterator<EntryWithTimestamp<MemorySegment>> {
    private EntryWithTimestamp<MemorySegment> prefetched;
    private final Iterator<EntryWithTimestamp<MemorySegment>> iterator;

    public SkipDeletedIterator(
            Iterator<EntryWithTimestamp<MemorySegment>> iterator
    ) {
        this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
        this.skipDeleted();
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

    public Entry<MemorySegment> peekNext() {
        if (this.prefetched == null) {
            this.prefetched = this.iterator.next();
        }
        return this.prefetched;
    }

    public void skipDeleted() {
        while (this.iterator.hasNext()) {
            Entry<MemorySegment> next = this.peekNext();
            if (next.value() == null) {
                this.prefetched = null;
            } else break;
        }
    }
}
