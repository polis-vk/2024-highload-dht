package ru.vk.itmo.test.abramovilya.dao;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.abramovilya.dao.table.MemTable;
import ru.vk.itmo.test.abramovilya.dao.table.SSTable;
import ru.vk.itmo.test.abramovilya.dao.table.TableEntry;


import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

class DaoIterator implements Iterator<Entry<MemorySegment>> {
    private final PriorityQueue<TableEntry> priorityQueue = new PriorityQueue<>();
    private final MemorySegment from;
    private final MemorySegment to;
    private final Storage storage;

    DaoIterator(int totalSStables,
                MemorySegment from,
                MemorySegment to,
                Storage storage,
                NavigableMap<MemorySegment, Entry<MemorySegment>> memTable,
                NavigableMap<MemorySegment, Entry<MemorySegment>> flushingTable
                ) {

        this.from = from;
        this.to = to;
        this.storage = storage;

        NavigableMap<MemorySegment, Entry<MemorySegment>> subMap = getSubMap(memTable);
        for (int i = 0; i < totalSStables; i++) {
            long offset = findOffsetInIndex(from, to, i);
            if (offset != -1) {
                priorityQueue.add(new SSTable(
                        i,
                        offset,
                        storage.mappedSStable(i),
                        storage.mappedIndex(i)
                ).currentEntry());
            }
        }
        if (!subMap.isEmpty()) {
            priorityQueue.add(new MemTable(subMap, Integer.MAX_VALUE).currentEntry());
        }

        if (flushingTable != null) {
            NavigableMap<MemorySegment, Entry<MemorySegment>> flushingSubMap = getSubMap(flushingTable);
            if (!flushingSubMap.isEmpty()) {
                priorityQueue.add(new MemTable(flushingSubMap, Integer.MAX_VALUE - 1).currentEntry());
            }
        }
        cleanUpSStableQueue();
    }

    private NavigableMap<MemorySegment, Entry<MemorySegment>> getSubMap(
            NavigableMap<MemorySegment, Entry<MemorySegment>> map) {
        NavigableMap<MemorySegment, Entry<MemorySegment>> subMap;
        if (from == null && to == null) {
            subMap = map;
        } else if (from == null) {
            subMap = map.headMap(to, false);
        } else if (to == null) {
            subMap = map.tailMap(from, true);
        } else {
            subMap = map.subMap(from, true, to, false);
        }
        return subMap;
    }

    @Override
    public boolean hasNext() {
        return !priorityQueue.isEmpty();
    }

    @Override
    public Entry<MemorySegment> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        TableEntry minEntry = priorityQueue.remove();
        MemorySegment key = minEntry.key();
        removeExpiredValues(key);

        TableEntry minEntryTableNextEntry = minEntry.table().nextEntry();
        if (minEntryTableNextEntry != null
                && (to == null || DaoImpl.compareMemorySegments(minEntryTableNextEntry.key(), to) < 0)) {
            priorityQueue.add(minEntryTableNextEntry);
        }
        MemorySegment value = minEntry.value();
        cleanUpSStableQueue();
        return new BaseEntry<>(key, value);
    }

    private void removeExpiredValues(MemorySegment minMemorySegment) {
        while (!priorityQueue.isEmpty() && priorityQueue.peek().key().mismatch(minMemorySegment) == -1) {
            TableEntry entryWithSameMin = priorityQueue.remove();

            TableEntry entryWithSameMinNext = entryWithSameMin.table().nextEntry();
            if (entryWithSameMinNext != null
                    && (to == null || DaoImpl.compareMemorySegments(entryWithSameMinNext.key(), to) < 0)) {
                priorityQueue.add(entryWithSameMinNext);
            }
        }
    }

    private void cleanUpSStableQueue() {
        while (!priorityQueue.isEmpty() && priorityQueue.peek().value() == null) {
            TableEntry minEntry = priorityQueue.remove();
            removeExpiredValues(minEntry.key());

            TableEntry minEntryNext = minEntry.table().nextEntry();
            if (minEntryNext != null
                    && (to == null || DaoImpl.compareMemorySegments(minEntryNext.key(), to) < 0)) {
                priorityQueue.add(minEntryNext);
            }
        }
    }

    private long findOffsetInIndex(MemorySegment from, MemorySegment to, int fileNum) {
        return storage.findOffsetInIndex(from, to, fileNum);
    }
}
