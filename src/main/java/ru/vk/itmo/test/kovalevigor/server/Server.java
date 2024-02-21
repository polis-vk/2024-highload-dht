package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpServer;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalevigor.config.DaoServerConfig;
import ru.vk.itmo.test.kovalevigor.dao.DaoImpl;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;

public class Server extends HttpServer implements Closeable {
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public Server(DaoServerConfig config) throws IOException {
        super(config);
        dao = new DaoImpl(mapConfig(config));
    }

    private static Config mapConfig(DaoServerConfig config) {
        return new Config(
            config.basePath,
            config.flushThresholdBytes
        );
    }

    @Override
    public void close() throws IOException {
        dao.close();
    }
}
