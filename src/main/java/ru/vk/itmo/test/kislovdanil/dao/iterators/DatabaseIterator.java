package ru.vk.itmo.test.kislovdanil.dao.iterators;

import ru.vk.itmo.test.kislovdanil.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public interface DatabaseIterator extends Iterator<Entry<MemorySegment>> {
    long getPriority();
}
