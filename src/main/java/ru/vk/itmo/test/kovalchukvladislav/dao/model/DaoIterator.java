package ru.vk.itmo.test.kovalchukvladislav.dao.model;

import ru.vk.itmo.dao.Entry;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;


public class DaoIterator<D, E extends Entry<D>> implements Iterator<E> {
    private final List<Iterator<E>> iterators;
    private final EntryExtractor<D, E> extractor;
    private final PriorityQueue<IndexedEntry> queue;

    public DaoIterator(List<Iterator<E>> iteratorsSortedByPriority, EntryExtractor<D, E> extractor) {
        this.iterators = iteratorsSortedByPriority;
        this.extractor = extractor;

        int size = iterators.size();
        this.queue = new PriorityQueue<>(size);
        for (int i = 0; i < size; i++) {
            addEntry(iterators.get(i), i);
        }
        cleanByNull();
    }

    @Override
    public boolean hasNext() {
        return !queue.isEmpty();
    }

    @Override
    public E next() {
        if (queue.isEmpty()) {
            throw new NoSuchElementException();
        }
        IndexedEntry minElement = queue.peek();
        E minEntry = minElement.entry;
        cleanByKey(minElement.entry.key());
        cleanByNull();
        return minEntry;
    }

    private void cleanByKey(D key) {
        while (!queue.isEmpty() && extractor.compare(queue.peek().entry.key(), key) == 0) {
            IndexedEntry removedEntry = queue.remove();
            int iteratorId = removedEntry.iteratorId;
            addEntryByIteratorIdSafe(iteratorId);
        }
    }

    private void cleanByNull() {
        while (!queue.isEmpty()) {
            E entry = queue.peek().entry;
            if (entry.value() != null) {
                break;
            }
            cleanByKey(entry.key());
        }
    }

    private void addEntryByIteratorIdSafe(int iteratorId) {
        addEntry(getIteratorById(iteratorId), iteratorId);
    }

    private void addEntry(Iterator<E> iterator, int id) {
        if (iterator.hasNext()) {
            E next = iterator.next();
            queue.add(new IndexedEntry(id, next));
        }
    }

    private Iterator<E> getIteratorById(int id) {
        return iterators.get(id);
    }

    private class IndexedEntry implements Comparable<IndexedEntry> {
        final int iteratorId;
        final E entry;

        public IndexedEntry(int iteratorId, E entry) {
            this.iteratorId = iteratorId;
            this.entry = entry;
        }

        @Override
        public int compareTo(IndexedEntry other) {
            int compared = extractor.compare(entry.key(), other.entry.key());
            if (compared != 0) {
                return compared;
            }
            return Integer.compare(iteratorId, other.iteratorId);
        }
    }
}
