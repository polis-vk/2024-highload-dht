package ru.vk.itmo.test.kovalchukvladislav.dao.storage;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;


public interface SSTableStorage<D, E extends Entry<D>> {
    /**
     * Добавляет новый SSTable из basePath в метадату и рефрешит SSTableStorage.
     * Используется при flush(), рефреш полезно выключать при flush() внутри close(), когда больше не будет запросов.
     */
    void addSSTableId(String id, boolean needRefresh) throws IOException;

    E get(D key);

    List<Iterator<E>> getIterators(D from, D to);

    void compact() throws IOException;

    void close();
}
