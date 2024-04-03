package ru.vk.itmo.test.tarazanovmaxim.dao;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Filters non tombstone {@link TimestampEntry}s.
 *
 * @author incubos
 */
final class LiveFilteringIterator implements Iterator<TimestampEntry<MemorySegment>> {
    private final Iterator<TimestampEntry<MemorySegment>> delegate;
    private TimestampEntry<MemorySegment> next;

    LiveFilteringIterator(final Iterator<TimestampEntry<MemorySegment>> delegate) {
        this.delegate = delegate;
        skipTombstones();
    }

    private void skipTombstones() {
        while (delegate.hasNext()) {
            final TimestampEntry<MemorySegment> entry = delegate.next();
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
    public TimestampEntry<MemorySegment> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        // Consume
        final TimestampEntry<MemorySegment> result = next;
        next = null;

        skipTombstones();

        return result;
    }
}
