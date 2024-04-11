package ru.vk.itmo.test.khadyrovalmasgali.service;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.khadyrovalmasgali.dao.ReferenceDao;
import ru.vk.itmo.test.khadyrovalmasgali.dao.TimestampEntry;
import ru.vk.itmo.test.khadyrovalmasgali.server.DaoServer;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;

public class DaoService implements Service {

    private DaoServer server;
    private Dao<MemorySegment, TimestampEntry<MemorySegment>> dao;
    private boolean stopped;
    private final ServiceConfig config;
    private static final int FLUSH_THRESHOLD_BYTES = 1024 * 1024; // 1MB

    public DaoService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        server = new DaoServer(config, dao);
        server.start();
        stopped = false;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        if (stopped) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            server.stop();
        } finally {
            dao.close();
        }
        stopped = true;
        return CompletableFuture.completedFuture(null);
    }
}
