package ru.vk.itmo.test.andreycheshev.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * Merges entry {@link Iterator}s.
 *
 * @author incubos
 */
final class MergingEntryIterator implements Iterator<Entry<MemorySegment>> {
    private final Queue<WeightedPeekingEntryIterator> iterators;

    MergingEntryIterator(final List<WeightedPeekingEntryIterator> iterators) {
        assert iterators.stream().allMatch(WeightedPeekingEntryIterator::hasNext);

        this.iterators = new PriorityQueue<>(iterators);
    }

    @Override
    public boolean hasNext() {
        return !iterators.isEmpty();
    }

    @Override
    public Entry<MemorySegment> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        final WeightedPeekingEntryIterator top = iterators.remove();
        final Entry<MemorySegment> result = top.next();

        if (top.hasNext()) {
            // Not exhausted
            iterators.add(top);
        }

        // Remove older versions of the key
        while (true) {
            final WeightedPeekingEntryIterator iterator = iterators.peek();
            if (iterator == null) {
                // Nothing left
                break;
            }

            // Skip entries with the same key
            final Entry<MemorySegment> entry = iterator.peek();
            if (MemorySegmentComparator.INSTANCE.compare(result.key(), entry.key()) != 0) {
                // Reached another key
                break;
            }

            // Drop
            iterators.remove();
            // Skip
            iterator.next();
            if (iterator.hasNext()) {
                // Not exhausted
                iterators.add(iterator);
            }
        }

        return result;
    }
}
