package ru.vk.itmo.test.abramovilya.dao.table;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;

public class MemTableEntry implements TableEntry {
    private final Entry<MemorySegment> current;
    private final MemTable memTable;

    public MemTableEntry(Entry<MemorySegment> entry, MemTable memTable) {
        this.current = entry;
        this.memTable = memTable;
    }

    @Override
    public MemorySegment value() {
        if (current == null) {
            return null;
        }
        return current.value();
    }

    @Override
    public MemorySegment key() {
        if (current == null) {
            return null;
        }
        return current.key();
    }

    @Override
    public int number() {
        return memTable.number;
    }

    @Override
    public Table table() {
        return memTable;
    }
}
