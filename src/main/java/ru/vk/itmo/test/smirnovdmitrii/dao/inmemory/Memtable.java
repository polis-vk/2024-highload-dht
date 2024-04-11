package ru.vk.itmo.test.smirnovdmitrii.dao.inmemory;

import ru.vk.itmo.test.smirnovdmitrii.dao.TimeEntry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.concurrent.locks.Lock;

/**
 * Representation of memtable.
 */
public interface Memtable extends Iterable<TimeEntry<MemorySegment>> {

    long size();

    void upsert(TimeEntry<MemorySegment> entry);

    TimeEntry<MemorySegment> get(MemorySegment key);

    Iterator<TimeEntry<MemorySegment>> get(MemorySegment from, MemorySegment to);

    void clear();

    Lock upsertLock();

    Lock flushLock();
}

