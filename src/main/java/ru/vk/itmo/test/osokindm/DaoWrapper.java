package ru.vk.itmo.test.osokindm;

import one.nio.http.Request;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.osokindm.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

public class DaoWrapper {

    private final ReferenceDao storage;

    DaoWrapper(Config config) throws IOException {
        storage = new ReferenceDao(config);
    }

    public Entry<MemorySegment> get(String id) {
        MemorySegment key = Converter.getMemorySegment(id);
        return storage.get(key);
    }

    public Boolean delete(String id) {
        MemorySegment key = Converter.getMemorySegment(id);
        storage.upsert(new BaseEntry<>(key, null));
        return true;
    }

    public Boolean upsert(String id, Request request) {
        MemorySegment key = Converter.getMemorySegment(id);
        MemorySegment value = Converter.getMemorySegment(request.getBody());
        storage.upsert(new BaseEntry<>(key, value));
        return true;
    }

    public void stop() throws IOException {
        storage.close();
    }
}
