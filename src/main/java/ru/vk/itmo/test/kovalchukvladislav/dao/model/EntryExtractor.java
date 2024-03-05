package ru.vk.itmo.test.kovalchukvladislav.dao.model;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Comparator;

public interface EntryExtractor<D, E extends Entry<D>> extends Comparator<D> {
    D readValue(MemorySegment memorySegment, long offset);

    long writeValue(D value, MemorySegment memorySegment, long offset);

    E readEntry(MemorySegment memorySegment, long offset);

    long writeEntry(E entry, MemorySegment memorySegment, long offset);

    long findLowerBoundValueOffset(D key, MemorySegment storage, MemorySegment offsets);

    long size(D value);

    long size(E entry);

    E createEntry(D key, D value);
}
