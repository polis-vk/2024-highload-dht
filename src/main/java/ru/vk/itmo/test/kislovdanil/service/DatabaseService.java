package ru.vk.itmo.test.kislovdanil.service;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kislovdanil.dao.PersistentDao;
import ru.vk.itmo.test.kislovdanil.service.sharding.RandevouzSharder;
import ru.vk.itmo.test.kislovdanil.service.sharding.Sharder;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;

public class DatabaseService implements Service {
    private DatabaseHttpServer httpServer;
    private final ServiceConfig serverConfig;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final Config daoConfig;
    private HttpClient httpClient;

    public DatabaseService(ServiceConfig serverConfig, Config daoConfig) throws IOException {
        this.serverConfig = serverConfig;
        this.daoConfig = daoConfig;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new PersistentDao(daoConfig);
        httpClient = HttpClient.newHttpClient();
        Sharder sharder = new RandevouzSharder(httpClient, serverConfig.clusterUrls());
        httpServer = new DatabaseHttpServer(serverConfig, dao, sharder);
        httpServer.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        dao.close();
        httpServer.stop();
        httpClient.close();
        return CompletableFuture.completedFuture(null);
    }

}
