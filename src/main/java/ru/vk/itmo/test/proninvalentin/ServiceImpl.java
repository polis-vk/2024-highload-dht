package ru.vk.itmo.test.proninvalentin;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    private final Config daoConfig;
    private final ServiceConfig config;
    private ReferenceDao dao;
    private Server server;
    private WorkerPool workerPool;

    public ServiceImpl(ServiceConfig config) throws IOException {
        int flushThresholdBytes = 1 << 20; // 1 MB
        this.config = config;
        this.daoConfig = new Config(config.workingDir(), flushThresholdBytes);
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        workerPool = new WorkerPool(WorkerPoolConfig.defaultConfig());
        server = new Server(config, dao, workerPool);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        dao.close();
        workerPool.gracefulShutdown();
        server.stop();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 3)
    public static class Factory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            try {
                return new ServiceImpl(config);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
