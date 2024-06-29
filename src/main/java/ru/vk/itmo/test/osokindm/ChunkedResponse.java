package ru.vk.itmo.test.osokindm;

import one.nio.http.Response;
import ru.vk.itmo.test.osokindm.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class ChunkedResponse extends Response {

    private static final String CHUNKED_HEADER = "Transfer-Encoding: chunked";
    private static final String CONTENT_TYPE_HEADER = "Content-Type: result-range";
    private final Iterator<Entry<MemorySegment>> rangeResult;

    public ChunkedResponse(String resultCode, Iterator<Entry<MemorySegment>> rangeResult) {
        super(resultCode);
        super.addHeader(CHUNKED_HEADER);
        super.addHeader(CONTENT_TYPE_HEADER);
        this.rangeResult = rangeResult;
    }

    public Iterator<Entry<MemorySegment>> getResultIterator() {
        return rangeResult;
    }

 }
