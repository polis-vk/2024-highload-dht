package ru.vk.itmo.test.kovalevigor.dao.iterators;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalevigor.dao.UtilsMemorySegment;

import java.lang.foreign.MemorySegment;
import java.util.Collection;

public class MergeEntryIterator<T extends Entry<MemorySegment>> extends MergeIterator<T, PriorityShiftedIterator<T>> {
    public MergeEntryIterator(Collection<PriorityShiftedIterator<T>> collection) {
        super(collection);
    }

    @Override
    public boolean hasNext() {
        checkAndSkip();
        return super.hasNext();
    }

    @Override
    protected boolean checkEquals(
            final PriorityShiftedIterator<T> lhs,
            final PriorityShiftedIterator<T> rhs
    ) {
        return UtilsMemorySegment.compareEntry(lhs.value, rhs.value) == 0;
    }

    private void checkAndSkip() {
        PriorityShiftedIterator<T> next = queue.peek();
        while (next != null && super.hasNext() && next.value.value() == null) {
            next();
            next = queue.peek();
        }
    }
}
