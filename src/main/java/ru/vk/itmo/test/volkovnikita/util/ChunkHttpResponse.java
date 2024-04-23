package ru.vk.itmo.test.volkovnikita.util;

import one.nio.http.Response;
import ru.vk.itmo.test.volkovnikita.dao.TimestampEntry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class ChunkHttpResponse extends Response {
    private final Iterator<TimestampEntry<MemorySegment>> iterator;
    private static final String CONTENT_TYPE = "Content-Type: text/plain";
    private static final String TRANSFER_ENCODING = "Transfer-Encoding: chunked";

    public ChunkHttpResponse(String resultCode, Iterator<TimestampEntry<MemorySegment>> entry) {
        super(resultCode);
        super.addHeader(CONTENT_TYPE);
        super.addHeader(TRANSFER_ENCODING);
        this.iterator = entry;
    }

    public Iterator<TimestampEntry<MemorySegment>> getIterator() {
        return iterator;
    }
}
