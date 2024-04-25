package ru.vk.itmo.test.georgiidalbeev;

import one.nio.http.Response;
import ru.vk.itmo.test.georgiidalbeev.dao.ReferenceBaseEntry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class MyChunkedResponse extends Response {
    private final Iterator<ReferenceBaseEntry<MemorySegment>> entries;

    public MyChunkedResponse(String resultCode, Iterator<ReferenceBaseEntry<MemorySegment>> entries) {
        super(resultCode);
        this.entries = entries;
        super.addHeader("Content-Type: text/plain");
        super.addHeader("Transfer-Encoding: chunked");
        super.addHeader("Connection: keep-aive");
    }

    public Iterator<ReferenceBaseEntry<MemorySegment>> getIterator() {
        return entries;
    }
}
