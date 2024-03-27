package ru.vk.itmo.test.shishiginstepan.service;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.shishiginstepan.dao.EntryWithTimestamp;
import ru.vk.itmo.test.shishiginstepan.dao.InMemDaoImpl;
import ru.vk.itmo.test.shishiginstepan.server.DistributedDao;
import ru.vk.itmo.test.shishiginstepan.server.RemoteDaoNode;
import ru.vk.itmo.test.shishiginstepan.server.Server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;

public class DatabaseService implements ru.vk.itmo.Service {
    private Server server;
    private DistributedDao distributedDao;

    private final ServiceConfig config;

    public DatabaseService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() {
        Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> localDao =
                new InMemDaoImpl(
                        config.workingDir(),
                        1024 * 1024
                );
        distributedDao = new DistributedDao(localDao, config.selfUrl());
        for (String url : config.clusterUrls()) {
            if (url.equals(config.selfUrl())) {
                continue;
            }
            distributedDao.addNode(
                    new RemoteDaoNode(url), url
            );
        }
        distributedDao.addNode(localDao, config.selfUrl());
        try {
            server = new Server(config, distributedDao);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        distributedDao.close();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public ru.vk.itmo.Service create(ServiceConfig config) {
            return new DatabaseService(config);
        }
    }
}
