package ru.vk.itmo.test.chebotinalexandr.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class State {
    private static final Comparator<MemorySegment> comparator = NotOnlyInMemoryDao::comparator;
    private final SortedMap<MemorySegment, Entry<MemorySegment>> readEntries;
    private final SortedMap<MemorySegment, Entry<MemorySegment>> writeEntries;
    private final List<MemorySegment> sstables;

    private State(
            SortedMap<MemorySegment, Entry<MemorySegment>> readEntries,
            SortedMap<MemorySegment, Entry<MemorySegment>> writeEntries,
            List<MemorySegment> segments
    ) {
        this.readEntries = readEntries;
        this.writeEntries = writeEntries;
        this.sstables = segments;
    }

    private static SortedMap<MemorySegment, Entry<MemorySegment>> createMap() {
        return new ConcurrentSkipListMap<>(comparator);
    }

    public static State initial(List<MemorySegment> segments) {
        return new State(createMap(), createMap(), segments
        );
    }

    public State compact(MemorySegment compacted) {
        return new State(
                readEntries,
                writeEntries,
                compacted == null ? Collections.emptyList() : Collections.singletonList(compacted));
    }

    public State beforeFlush() {
        return new State(writeEntries, createMap(), sstables);
    }

    public State afterFlush(MemorySegment newPage) {
        List<MemorySegment> segments = new ArrayList<>(this.sstables.size() + 1);
        segments.addAll(this.sstables);
        segments.add(newPage);

        return new State(createMap(), writeEntries, segments);
    }

    public SortedMap<MemorySegment, Entry<MemorySegment>> getReadEntries() {
        return readEntries;
    }

    public SortedMap<MemorySegment, Entry<MemorySegment>> getWriteEntries() {
        return writeEntries;
    }

    public List<MemorySegment> getSstables() {
        return sstables;
    }
}
