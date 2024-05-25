package ru.vk.itmo.test.elenakhodosova.dao;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Filters non tombstone {@link EntryWithTimestamp}s.
 *
 * @author incubos
 */
final class LiveFilteringIterator implements Iterator<EntryWithTimestamp<MemorySegment>> {
    private final Iterator<EntryWithTimestamp<MemorySegment>> delegate;
    private EntryWithTimestamp<MemorySegment> next;

    LiveFilteringIterator(final Iterator<EntryWithTimestamp<MemorySegment>> delegate) {
        this.delegate = delegate;
        skipTombstones();
    }

    private void skipTombstones() {
        while (delegate.hasNext()) {
            final EntryWithTimestamp<MemorySegment> entry = delegate.next();
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
    public EntryWithTimestamp<MemorySegment> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        // Consume
        final EntryWithTimestamp<MemorySegment> result = next;
        next = null;

        skipTombstones();

        return result;
    }
}
