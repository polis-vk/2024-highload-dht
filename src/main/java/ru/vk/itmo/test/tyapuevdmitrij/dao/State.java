package ru.vk.itmo.test.tyapuevdmitrij.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public class State {
    final ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> memTable;
    final ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> flushMemTable;
    final AtomicLong memoryUsage;
    final Storage storage;

    State(ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> memTable,
          ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> flushMemTable, AtomicLong memoryUsage,
          Storage storage) {
        this.memTable = memTable;
        this.flushMemTable = flushMemTable;
        this.memoryUsage = memoryUsage;
        this.storage = storage;
    }

    public State prepareToFlash() {
        return new State(new ConcurrentSkipListMap<>(MemorySegmentComparator.getMemorySegmentComparator()),
                this.memTable,
                new AtomicLong(),
                this.storage);
    }

    public State afterFLush(MemorySegment flushedTable) {
        List<MemorySegment> newSsTables = new ArrayList<>(storage.ssTables.size() + 1);
        newSsTables.addAll(storage.ssTables);
        newSsTables.add(flushedTable);
        Storage newStorage = new Storage(storage.storageHelper,
                storage.ssTablesQuantity + 1,
                newSsTables,
                storage.readArena);
        return new State(this.memTable, null, this.memoryUsage, newStorage);
    }
}


