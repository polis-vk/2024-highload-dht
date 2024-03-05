package ru.vk.itmo.test.chebotinalexandr.dao;

import java.util.Iterator;

public interface PeekingIterator<E> extends Iterator<E> {
    E peek();

    default int priority() {
        return 0;
    }
}
