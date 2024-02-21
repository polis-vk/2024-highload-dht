package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;

public class SSTableManager implements DaoFileGet<MemorySegment, Entry<MemorySegment>>, AutoCloseable {

    public static final String SSTABLE_NAME = "sstable";

    private final Path root;
    private final Arena arena;
    private final List<SSTable> ssTables;

    public SSTableManager(final Path root) throws IOException {
        this.root = root;
        this.arena = Arena.ofShared();
        this.ssTables = readTables();
    }

    private List<SSTable> readTables() throws IOException {
        final List<SSTable> tables = new ArrayList<>();
        SSTable table;
        while ((table = readTable(getNextSSTableName(tables.size()))) != null) {
            tables.add(table);
        }
        return tables.reversed();
    }

    private SSTable readTable(final String name) throws IOException {
        return SSTable.create(root, name, arena);
    }

    private String getNextSSTableName() {
        return getNextSSTableName(ssTables.size());
    }

    private String getNextSSTableName(final int size) {
        return SSTABLE_NAME + size;
    }

    public void write(SortedMap<MemorySegment, Entry<MemorySegment>> map) throws IOException {
        SSTable.write(map, root, getNextSSTableName());
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(final MemorySegment from, final MemorySegment to) throws IOException {

        List<PriorityShiftedIterator<Entry<MemorySegment>>> iterators = new ArrayList<>();
        for (int i = 0; i < ssTables.size(); i++) {
            iterators.add(new MemEntryPriorityIterator(i, ssTables.get(i).get(from, to)));
        }

        return new MergeEntryIterator(iterators);
    }

    @Override
    public Entry<MemorySegment> get(final MemorySegment key) throws IOException {
        Entry<MemorySegment> value = null;
        for (final SSTable ssTable: ssTables) {
            value = ssTable.get(key);
            if (value != null) {
                if (value.value() == null) {
                    value = null;
                }
                break;
            }
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        if (!arena.scope().isAlive()) {
            return;
        }
        ssTables.clear();
        arena.close();
    }
}
