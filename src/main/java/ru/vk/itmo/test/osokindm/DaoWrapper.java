package ru.vk.itmo.test.osokindm;

import one.nio.http.Request;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.osokindm.dao.BaseEntry;
import ru.vk.itmo.test.osokindm.dao.Entry;
import ru.vk.itmo.test.osokindm.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class DaoWrapper {

    private final ReferenceDao storage;

    DaoWrapper(Config config) throws IOException {
        storage = new ReferenceDao(config);
    }

    public Entry<MemorySegment> get(String id) {
        MemorySegment key = getMemorySegment(id);
        return storage.get(key);
    }

    public Iterator<Entry<MemorySegment>> get(String start, String end) {
        MemorySegment startKey = getMemorySegment(start);
        MemorySegment endKey = getMemorySegment(end);
        return storage.get(startKey, endKey);
    }

    public void delete(String id, long timestamp) {
        MemorySegment key = getMemorySegment(id);
        storage.upsert(new BaseEntry<>(key, null, timestamp));
    }

    public void upsert(String id, Request request, long timestamp) {
        MemorySegment key = getMemorySegment(id);
        MemorySegment value = getMemorySegment(request.getBody());
        storage.upsert(new BaseEntry<>(key, value, timestamp));
    }

    public void stop() throws IOException {
        storage.close();
    }

    private static MemorySegment getMemorySegment(byte[] data) {
        return MemorySegment.ofArray(data);
    }

    private static MemorySegment getMemorySegment(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

}
