package ru.vk.itmo.test.smirnovdmitrii.dao.util;

public interface MinHeap<T> {

    /**
     * return minimal element of the heap without removing.
     * @return minimum of the heap. Null if heap is empty.
     */
    T min();

    /**
     * returns minimal element of the heap with removing.
     * @return minimum of the heap. Null if heap is empty.
     */
    T removeMin();

    /**
     * add element to the heap.
     * @param t element to add.
     */
    void add(T t);

    /**
     * returns true if heap is empty.
     * @return true if heap is empty.
     */
    boolean isEmpty();

}
