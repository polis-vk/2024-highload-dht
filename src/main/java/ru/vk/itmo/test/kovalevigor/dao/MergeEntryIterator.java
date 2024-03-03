package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Collection;

public class MergeEntryIterator extends MergeIterator<
        Entry<MemorySegment>,
        PriorityShiftedIterator<Entry<MemorySegment>>
        > {
    public MergeEntryIterator(Collection<? extends PriorityShiftedIterator<Entry<MemorySegment>>> collection) {
        super(collection);
    }

    @Override
    public boolean hasNext() {
        checkAndSkip();
        return super.hasNext();
    }

    @Override
    protected boolean checkEquals(
            final PriorityShiftedIterator<Entry<MemorySegment>> lhs,
            final PriorityShiftedIterator<Entry<MemorySegment>> rhs
    ) {
        return UtilsMemorySegment.compareEntry(lhs.value, rhs.value) == 0;
    }

    private void checkAndSkip() {
        PriorityShiftedIterator<Entry<MemorySegment>> next = queue.peek();
        while (next != null && super.hasNext() && next.value.value() == null) {
            next();
            next = queue.peek();
        }
    }
}
