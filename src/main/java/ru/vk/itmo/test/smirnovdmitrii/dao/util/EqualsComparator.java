package ru.vk.itmo.test.smirnovdmitrii.dao.util;

import java.util.Comparator;

public interface EqualsComparator<T> extends Comparator<T> {

    /**
     * Method equals returns true if compare returns 0. It could more effective.
     * @param o1 first object for equals.
     * @param o2 second object for equals.
     * @return result of compare.
     */
    boolean equals(T o1, T o2);
}
