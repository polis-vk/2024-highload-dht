package ru.vk.itmo.test.dariasupriadkina.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Filters non tombstone {@link Entry}s.
 *
 * @author incubos
 */
final class LiveFilteringIterator implements Iterator<ExtendedEntry<MemorySegment>> {
    private final Iterator<ExtendedEntry<MemorySegment>> delegate;
    private ExtendedEntry<MemorySegment> next;

    LiveFilteringIterator(final Iterator<ExtendedEntry<MemorySegment>> delegate) {
        this.delegate = delegate;
        skipTombstones();
    }

    private void skipTombstones() {
        while (delegate.hasNext()) {
            final ExtendedEntry<MemorySegment> entry = delegate.next();
            if (entry.value() != null) {
                this.next = entry;
                break;
            }
        }
    }

    @Override
    public boolean hasNext() {
        return next != null;
    }

    @Override
    public ExtendedEntry<MemorySegment> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        // Consume
        final ExtendedEntry<MemorySegment> result = next;
        next = null;

        skipTombstones();

        return result;
    }
}
