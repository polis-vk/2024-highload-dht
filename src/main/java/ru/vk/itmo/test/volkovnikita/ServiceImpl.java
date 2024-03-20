package ru.vk.itmo.test.volkovnikita;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceImpl implements Service {

    public static final long FLUSH_THRESHOLD_BYTES = 2 * 1024 * 1024L;
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    private HttpServerImpl server;
    private final ServiceConfig config;
    private ReferenceDao dao;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        server = new HttpServerImpl(config, dao);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (isStopped.getAndSet(true)) {
            return CompletableFuture.completedFuture(null);
        }
        server.stop();
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
