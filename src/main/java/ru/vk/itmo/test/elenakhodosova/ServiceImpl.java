package ru.vk.itmo.test.elenakhodosova;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceImpl implements Service {

    private HttpServerImpl server;
    private ReferenceDao dao;
    private final ServiceConfig config;
    private ExecutorService executorService;
    public static final long FLUSH_THRESHOLD_BYTES = 4 * 1024 * 1024;
    public static final int TERMINATION_TIMEOUT_MS = 500;
    private final AtomicBoolean isServiceStopped = new AtomicBoolean(false);

    public ServiceImpl(ServiceConfig config) {
        this.config = config;

    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        executorService = ExecutorServiceConfig.newExecutorService();
        server = new HttpServerImpl(config, dao, executorService);
        server.start();
        isServiceStopped.getAndSet(false);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (isServiceStopped.getAndSet(true)) {
            return CompletableFuture.completedFuture(null);
        }
        server.stop();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 6)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
