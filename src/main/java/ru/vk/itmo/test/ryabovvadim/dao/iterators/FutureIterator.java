package ru.vk.itmo.test.ryabovvadim.dao.iterators;

import java.util.Iterator;

public interface FutureIterator<T> extends Iterator<T> {
    T showNext();
}
