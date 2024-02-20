package ru.vk.itmo.test.shishiginstepan.service;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.shishiginstepan.dao.InMemDaoImpl;
import ru.vk.itmo.test.shishiginstepan.server.Server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;

public class DatabaseService implements ru.vk.itmo.Service {
    private Server server;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;

    private final ServiceConfig config;

    public DatabaseService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() {
        dao = new InMemDaoImpl(config.workingDir(), 1024);
        try {
            server = new Server(config, dao);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public ru.vk.itmo.Service create(ServiceConfig config) {
            return new DatabaseService(config);
        }
    }
}
