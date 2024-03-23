package ru.vk.itmo.test.emelyanovvitaliy.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Filters non tombstone {@link Entry}s.
 *
 * @author incubos
 */
final class LiveFilteringIterator implements Iterator<TimestampedEntry<MemorySegment>> {
    private final Iterator<TimestampedEntry<MemorySegment>> delegate;
    private TimestampedEntry<MemorySegment> next;

    LiveFilteringIterator(final Iterator<TimestampedEntry<MemorySegment>> delegate) {
        this.delegate = delegate;
        skipTombstones();
    }

    private void skipTombstones() {
        while (delegate.hasNext()) {
            final TimestampedEntry<MemorySegment> entry = delegate.next();
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
    public TimestampedEntry<MemorySegment> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        // Consume
        final TimestampedEntry<MemorySegment> result = next;
        next = null;

        skipTombstones();

        return result;
    }
}
