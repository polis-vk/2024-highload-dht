package ru.vk.itmo.test.kislovdanil.service;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kislovdanil.dao.PersistentDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;

public class DatabaseService implements Service {
    private DatabaseHttpServer httpServer;
    private final ServiceConfig serverConfig;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final Config daoConfig;

    public DatabaseService(ServiceConfig serverConfig, Config daoConfig) throws IOException {
        this.serverConfig = serverConfig;
        this.daoConfig = daoConfig;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new PersistentDao(daoConfig);
        httpServer = new DatabaseHttpServer(serverConfig, dao);
        httpServer.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        dao.close();
        httpServer.stop();
        return CompletableFuture.completedFuture(null);
    }

}
