package ru.vk.itmo.test.kislovdanil.dao.iterators;

import ru.vk.itmo.test.kislovdanil.dao.Entry;
import ru.vk.itmo.test.kislovdanil.dao.MemTable;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class MemTableIterator implements DatabaseIterator {
    private final Iterator<Entry<MemorySegment>> innerIter;
    private final long priority;

    public MemTableIterator(MemorySegment from, MemorySegment to,
                            MemTable memTable,
                            long priority) {
        this.priority = priority;
        if (from == null && to == null) {
            innerIter = memTable.getStorage().values().iterator();
        } else if (from != null && to == null) {
            innerIter = memTable.getStorage().tailMap(from).values().iterator();
        } else if (from == null) {
            innerIter = memTable.getStorage().headMap(to).values().iterator();
        } else {
            innerIter = memTable.getStorage().subMap(from, to).values().iterator();
        }
    }

    @Override
    public long getPriority() {
        return priority;
    }

    @Override
    public boolean hasNext() {
        return innerIter.hasNext();
    }

    @Override
    public Entry<MemorySegment> next() {
        return innerIter.next();
    }
}
