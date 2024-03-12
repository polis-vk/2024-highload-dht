package ru.vk.itmo.test.elenakhodosova;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private HttpServerImpl server;
    private ReferenceDao dao;
    private final ServiceConfig config;
    public static final long FLUSH_THRESHOLD_BYTES = 4 * 1024 * 1024;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;

    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        this.server = new HttpServerImpl(config, dao);
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
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
