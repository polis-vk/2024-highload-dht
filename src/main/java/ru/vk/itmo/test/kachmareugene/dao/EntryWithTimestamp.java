package ru.vk.itmo.test.kachmareugene.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;


public interface EntryWithTimestamp<D> extends Entry<D> {
    long timestamp();
}