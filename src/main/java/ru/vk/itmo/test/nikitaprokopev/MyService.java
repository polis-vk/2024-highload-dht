package ru.vk.itmo.test.nikitaprokopev;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.nikitaprokopev.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;

public class MyService implements Service {

    private static final long FLUSH_THRESHOLD_BYTES = 2 * 1024 * 1024; // 2 MB
    private MyServer server;
    private final ServiceConfig config;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;

    public MyService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        server = new MyServer(config, dao);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }
}
