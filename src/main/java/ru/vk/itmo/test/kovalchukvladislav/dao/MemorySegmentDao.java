package ru.vk.itmo.test.kovalchukvladislav.dao;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

public class MemorySegmentDao extends AbstractBasedOnSSTableDao<MemorySegment, Entry<MemorySegment>> {
    public MemorySegmentDao(Config config) throws IOException {
        super(config, MemorySegmentEntryExtractor.INSTANCE);
    }
}
