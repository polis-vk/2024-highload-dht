package ru.vk.itmo.test.trofimovmaxim;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.trofimovmaxim.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TrofikService implements Service {

    private static final long FLUSHING_THRESHOLD_BYTES = 1024 * 1024;

    private final ServiceConfig config;

    private ReferenceDao dao;
    private TrofikServer server;
    private boolean stopped;

    public TrofikService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSHING_THRESHOLD_BYTES));
        server = new TrofikServer(config, dao);
        server.start();
        stopped = false;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (stopped) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            server.stop();

        } finally {
            dao.close();
        }
        stopped = true;
        return CompletableFuture.completedFuture(null);
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new TrofikService(config);
        }
    }
}
