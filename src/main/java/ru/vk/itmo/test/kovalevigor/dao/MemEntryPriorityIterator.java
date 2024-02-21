package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class MemEntryPriorityIterator extends PriorityShiftedIterator<Entry<MemorySegment>> {

    public MemEntryPriorityIterator(int priority) {
        super(priority);
    }

    public MemEntryPriorityIterator(int priority, Iterator<Entry<MemorySegment>> iterator) {
        super(priority, iterator);
    }

    @Override
    public int compareTo(final PriorityShiftedIterator rhs) {
        if (rhs instanceof MemEntryPriorityIterator rhsEntry) {
            final int comp = UtilsMemorySegment.compareEntry(value, rhsEntry.value);
            if (comp != 0) {
                return comp;
            }
        }
        return super.compareTo(rhs);
    }
}
