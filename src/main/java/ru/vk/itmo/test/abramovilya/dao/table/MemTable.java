package ru.vk.itmo.test.abramovilya.dao.table;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NavigableMap;

public class MemTable implements Table {
    private final Iterator<Entry<MemorySegment>> iterator;
    private MemTableEntry currentEntry;
    final int number;

    public MemTable(NavigableMap<MemorySegment, Entry<MemorySegment>> map, int number) {
        this.iterator = map.values().iterator();
        this.number = number;
        if (iterator.hasNext()) {
            currentEntry = new MemTableEntry(iterator.next(), this);
        } else {
            currentEntry = new MemTableEntry(null, this);
        }
    }

    @Override
    public MemTableEntry nextEntry() {
        if (!iterator.hasNext()) {
            return null;
        }
        MemTableEntry nextEntry = new MemTableEntry(iterator.next(), this);
        currentEntry = nextEntry;
        return nextEntry;
    }

    @Override
    public TableEntry currentEntry() {
        return currentEntry;
    }
}
