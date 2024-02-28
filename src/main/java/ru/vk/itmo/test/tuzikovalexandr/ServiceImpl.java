package ru.vk.itmo.test.tuzikovalexandr;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private final ServiceConfig config;
    private ServerImpl server;
    private Dao dao;
    private Worker worker;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), 1024 * 1024));
        worker = new Worker(new WorkerConfig());
        server = new ServerImpl(config, dao, worker);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        worker.getExecutorService().close();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 2)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
