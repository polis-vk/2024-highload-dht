package ru.vk.itmo.test.tuzikovalexandr;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

public class DaoFactory {

    public MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

}
