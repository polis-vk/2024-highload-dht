package ru.vk.itmo.test.ryabovvadim.dao.iterators;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class EntrySkipNullsIterator implements FutureIterator<Entry<MemorySegment>> {
    private final FutureIterator<Entry<MemorySegment>> delegate;

    public EntrySkipNullsIterator(Iterator<Entry<MemorySegment>> delegate) {
        this.delegate = new LazyIterator<>(delegate);
    }

    public EntrySkipNullsIterator(FutureIterator<Entry<MemorySegment>> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Entry<MemorySegment> showNext() {
        skipNulls();
        return delegate.showNext();
    }

    @Override
    public boolean hasNext() {
        skipNulls();
        return delegate.hasNext();
    }

    @Override
    public Entry<MemorySegment> next() {
        skipNulls();
        return delegate.next();
    }

    private void skipNulls() {
        while (delegate.hasNext() && delegate.showNext().value() == null) {
            delegate.next();
        }
    }
}
