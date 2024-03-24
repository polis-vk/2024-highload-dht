package ru.vk.itmo.test.kislovdanil.dao.exceptions;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;

public class OverloadException extends DBException {
    public final Entry<MemorySegment> entry;

    public OverloadException(Entry<MemorySegment> entry) {
        super();
        this.entry = entry;
    }
}
