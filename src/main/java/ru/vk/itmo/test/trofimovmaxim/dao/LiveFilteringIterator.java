package ru.vk.itmo.test.trofimovmaxim.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Filters non tombstone {@link Entry}s.
 *
 * @author incubos
 */
final class LiveFilteringIterator implements Iterator<ReferenceBaseEntry<MemorySegment>> {
    private final Iterator<ReferenceBaseEntry<MemorySegment>> delegate;
    private ReferenceBaseEntry<MemorySegment> next;

    LiveFilteringIterator(final Iterator<ReferenceBaseEntry<MemorySegment>> delegate) {
        this.delegate = delegate;
        skipTombstones();
    }

    private void skipTombstones() {
        while (delegate.hasNext()) {
            final ReferenceBaseEntry<MemorySegment> entry = delegate.next();
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
    public ReferenceBaseEntry<MemorySegment> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        // Consume
        final ReferenceBaseEntry<MemorySegment> result = next;
        next = null;

        skipTombstones();

        return result;
    }
}
