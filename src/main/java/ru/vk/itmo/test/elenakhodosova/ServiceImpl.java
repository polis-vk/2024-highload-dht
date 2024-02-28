package ru.vk.itmo.test.elenakhodosova;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class ServiceImpl implements Service {

    private HttpServerImpl server;
    private ReferenceDao dao;
    private final ServiceConfig config;
    private ExecutorService executorService;
    public static final long FLUSH_THRESHOLD_BYTES = 4 * 1024 * 1024;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;

    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        executorService = ExecutorServiceConfig.getExecutorService();
        this.server = new HttpServerImpl(config, dao, executorService);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        executorService.shutdownNow();
        server.stop();
        try {
            dao.close();
        } catch (IOException e) {
            Thread.currentThread().interrupt();
        }
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
