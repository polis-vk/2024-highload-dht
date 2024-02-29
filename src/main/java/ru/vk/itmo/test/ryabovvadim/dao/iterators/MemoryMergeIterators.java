package ru.vk.itmo.test.ryabovvadim.dao.iterators;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ryabovvadim.dao.utils.MemorySegmentUtils;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class MemoryMergeIterators implements Iterator<Entry<MemorySegment>> {
    private final Iterator<Entry<MemorySegment>> fstIterator;
    private final Iterator<Entry<MemorySegment>> sndIterator;
    private Entry<MemorySegment> fstEntry;
    private Entry<MemorySegment> sndEntry;

    public MemoryMergeIterators(
            Iterator<Entry<MemorySegment>> fstIterator,
            Iterator<Entry<MemorySegment>> sndIterator
    ) {
        this.fstIterator = fstIterator;
        this.sndIterator = sndIterator;
    }

    @Override
    public boolean hasNext() {
        return fstIterator.hasNext()
                || sndIterator.hasNext()
                || fstEntry != null
                || sndEntry != null;
    }

    @Override
    public Entry<MemorySegment> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        getFstNext();
        getSndNext();

        Entry<MemorySegment> result;
        if (fstEntry == null) {
            result = sndEntry;
            sndEntry = null;
        } else if (sndEntry == null) {
            result = fstEntry;
            fstEntry = null;
        } else {
            result = compare();
        }

        return result;
    }

    private void getFstNext() {
        if (fstEntry == null && fstIterator.hasNext()) {
            fstEntry = fstIterator.next();
        }
    }

    private void getSndNext() {
        if (sndEntry == null && sndIterator.hasNext()) {
            sndEntry = sndIterator.next();
        }
    }

    private Entry<MemorySegment> compare() {
        int compareResult = MemorySegmentUtils.compareMemorySegments(fstEntry.key(), sndEntry.key());
        Entry<MemorySegment> result;
        if (compareResult < 0) {
            result = fstEntry;
            fstEntry = null;
        } else if (compareResult == 0) {
            result = fstEntry;
            fstEntry = null;
            sndEntry = null;
        } else {
            result = sndEntry;
            sndEntry = null;
        }

        return result;
    }
}
