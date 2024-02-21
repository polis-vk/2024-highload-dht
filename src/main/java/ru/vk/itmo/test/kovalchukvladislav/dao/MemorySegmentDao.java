package ru.vk.itmo.test.kovalchukvladislav.dao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;

public class MemorySegmentDao extends AbstractBasedOnSSTableDao<MemorySegment, Entry<MemorySegment>> {
    public MemorySegmentDao(Config config) throws IOException {
        super(config, MemorySegmentEntryExtractor.INSTANCE);
    }
}
