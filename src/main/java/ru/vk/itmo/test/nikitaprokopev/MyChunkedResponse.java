package ru.vk.itmo.test.nikitaprokopev;

import one.nio.http.Response;
import ru.vk.itmo.test.nikitaprokopev.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class MyChunkedResponse extends Response {
    private final Iterator<Entry<MemorySegment>> entries;

    public MyChunkedResponse(String resultCode, Iterator<Entry<MemorySegment>> entries) {
        super(resultCode);
        super.addHeader("Content-Type: text/plain");
        super.addHeader("Transfer-Encoding: chunked");
        this.entries = entries;
    }

    public Iterator<Entry<MemorySegment>> getIterator() {
        return entries;
    }
}
