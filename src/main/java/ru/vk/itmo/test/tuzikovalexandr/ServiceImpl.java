package ru.vk.itmo.test.tuzikovalexandr;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private static final long FLUSHING_THRESHOLD_BYTES = 1024 * 1024;

    private final ServiceConfig config;
    private ServerImpl server;
    private Dao dao;
    private Worker worker;
    private ConsistentHashing consistentHashing;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSHING_THRESHOLD_BYTES));
        worker = new Worker(new WorkerConfig());
        consistentHashing = new ConsistentHashing();
        server = new ServerImpl(config, dao, worker, consistentHashing);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        server.stop();
        worker.shutdownAndAwaitTermination();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 3)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
