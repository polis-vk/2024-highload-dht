package ru.vk.itmo.test.osokindm;

import one.nio.http.Request;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.osokindm.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class DaoWrapper {

    private final ReferenceDao storage;

    DaoWrapper(Config config) throws IOException {
        storage = new ReferenceDao(config);
    }

    public Entry<MemorySegment> get(String id) {
        MemorySegment key = getMemorySegment(id);
        return storage.get(key);
    }

    public Boolean delete(String id) {
        MemorySegment key = getMemorySegment(id);
        storage.upsert(new BaseEntry<>(key, null));
        return true;
    }

    public Boolean upsert(String id, Request request) {
        MemorySegment key = getMemorySegment(id);
        MemorySegment value = getMemorySegment(request.getBody());
        storage.upsert(new BaseEntry<>(key, value));
        return true;
    }

    public void stop() throws IOException {
        storage.close();
    }

    private static MemorySegment getMemorySegment(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    private static MemorySegment getMemorySegment(byte[] data) {
        return MemorySegment.ofArray(data);
    }

//    private static String getString(MemorySegment value) {
//        return value == null ? null
//                : new String((byte[]) value.heapBase()
//                .orElse(value.toArray(ValueLayout.JAVA_BYTE)), StandardCharsets.UTF_8);
//    }

}
