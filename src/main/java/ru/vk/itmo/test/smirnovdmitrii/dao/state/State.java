package ru.vk.itmo.test.smirnovdmitrii.dao.state;

import ru.vk.itmo.test.smirnovdmitrii.dao.inmemory.Memtable;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable.SSTable;

import java.util.List;

public record State(
        List<Memtable> memtables,
        List<SSTable> ssTables
) {
}
