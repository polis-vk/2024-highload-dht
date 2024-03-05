package ru.vk.itmo.test.smirnovdmitrii.dao.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class BinaryMinHeap<T> implements MinHeap<T> {
    final List<T> list = new ArrayList<>();
    final Comparator<T> comparator;

    public BinaryMinHeap(final Comparator<T> comparator) {
        this.comparator = comparator;
    }

    public BinaryMinHeap(final Collection<T> list, final Comparator<T> comparator) {
        this(comparator);
        this.list.addAll(list);
        heapify();
    }

    @Override
    public T min() {
        if (list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public T removeMin() {
        if (list.isEmpty()) {
            return null;
        }
        final T result = list.get(0);
        list.set(0, list.get(list.size() - 1));
        list.removeLast();
        siftDown(0);
        return result;
    }

    @Override
    public void add(final T t) {
        list.add(t);
        siftUp();
    }

    @Override
    public boolean isEmpty() {
        return list.isEmpty();
    }

    public void siftDown(final int i) {
        int index = i;
        final int size = list.size();
        while (true) {
            final int leftIndex = index * 2 + 1;
            if (leftIndex >= size) {
                return;
            }
            final int rightIndex = index * 2 + 2;
            final int nextIndex;
            if (rightIndex >= size || comparator.compare(list.get(leftIndex), list.get(rightIndex)) <= 0) {
                nextIndex = leftIndex;
            } else {
                nextIndex = rightIndex;
            }
            final T cur = list.get(index);
            if (comparator.compare(cur, list.get(nextIndex)) <= 0) {
                break;
            }
            list.set(index, list.get(nextIndex));
            list.set(nextIndex, cur);
            index = nextIndex;
        }
    }

    public void siftUp() {
        int index = list.size() - 1;
        while (comparator.compare(list.get(index), list.get((index - 1) / 2)) < 0) {
            final int nextIndex = (index - 1) / 2;
            final T next = list.get(nextIndex);
            list.set(nextIndex, list.get(index));
            list.set(index, next);
            index = nextIndex;
        }
    }

    private void heapify() {
       for (int i = list.size() / 2; i >= 0; i--) {
           siftDown(i);
       }
    }

}
