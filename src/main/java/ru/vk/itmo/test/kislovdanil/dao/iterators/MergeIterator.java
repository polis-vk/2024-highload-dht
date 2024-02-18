package ru.vk.itmo.test.kislovdanil.dao.iterators;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kislovdanil.dao.sstable.SSTable;

import java.lang.foreign.MemorySegment;
import java.util.*;

// Iterates through SSTables and MemTable using N pointers algorithm. Conflicts being solved by iterator priority.
public class MergeIterator implements Iterator<Entry<MemorySegment>> {
    private final NavigableMap<MemorySegment, IteratorAndValue> itemsPool;
    private Entry<MemorySegment> currentEntry;

    private static Iterable<DatabaseIterator> getDBIterators(Collection<SSTable> tables) {
        List<DatabaseIterator> it = new ArrayList<>(tables.size());
        for (SSTable table: tables) {
            it.add(table.getRange());
        }
        return it;
    }

    public MergeIterator(Iterable<DatabaseIterator> iterators, Comparator<MemorySegment> comp) {
        this.itemsPool = new TreeMap<>(comp);
        for (DatabaseIterator iter : iterators) {
            moveIterator(iter);
        }
        updateCurrentEntry();
    }

    public MergeIterator(Collection<SSTable> tables, Comparator<MemorySegment> comp) {
        this(getDBIterators(tables), comp);
    }

    // Get next entry (skip all entries with null value)
    private void updateCurrentEntry() {
        MemorySegment value = null;
        MemorySegment key = null;
        while (value == null && !itemsPool.isEmpty()) {
            key = itemsPool.firstKey();
            IteratorAndValue iteratorAndValue = itemsPool.get(key);
            itemsPool.remove(key);
            moveIterator(iteratorAndValue.iterator);
            value = iteratorAndValue.value;
        }
        currentEntry = (value == null) ? null : new BaseEntry<>(key, value);
    }

    // Move iterator to next value keeping invariant (several iterators mustn't point to equal keys at the same time)
    private void moveIterator(DatabaseIterator iter) {
        while (iter.hasNext()) {
            Entry<MemorySegment> entry = iter.next();
            boolean hasConcurrentKey = itemsPool.containsKey(entry.key());
            boolean winPriorityConflict = false;
            DatabaseIterator concurrentIterator = null;
            if (itemsPool.containsKey(entry.key())) {
                concurrentIterator = itemsPool.get(entry.key()).iterator;
                winPriorityConflict = iter.getPriority() > concurrentIterator.getPriority();
            }
            if (winPriorityConflict) {
                moveIterator(concurrentIterator);
            }
            if (!hasConcurrentKey || winPriorityConflict) {
                itemsPool.put(entry.key(), new IteratorAndValue(iter, entry.value()));
                break;
            }
        }
    }

    @Override
    public boolean hasNext() {
        return currentEntry != null;
    }

    @Override
    public Entry<MemorySegment> next() {
        Entry<MemorySegment> result = currentEntry;
        updateCurrentEntry();
        return result;
    }

    private static final class IteratorAndValue {
        private final DatabaseIterator iterator;
        private final MemorySegment value;

        public IteratorAndValue(DatabaseIterator iterator, MemorySegment value) {
            this.iterator = iterator;
            this.value = value;
        }
    }
}
