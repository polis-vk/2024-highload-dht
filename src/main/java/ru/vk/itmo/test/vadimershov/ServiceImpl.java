package ru.vk.itmo.test.vadimershov;

import one.nio.http.HttpServer;
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
    private HttpServer server;

    private boolean isRun;

    public ServiceImpl(ServiceConfig config) throws IOException {
        int flushThresholdBytes = 1 << 11;
        this.config = config;
        this.daoConfig = new Config(config.workingDir(), flushThresholdBytes);
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        if (isRun) {
            return CompletableFuture.completedFuture(null);
        }
        dao = new ReferenceDao(daoConfig);
        server = new DaoHttpServer(config, dao);
        server.start();
        isRun = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (!isRun) {
            return CompletableFuture.completedFuture(null);
        }
        server.stop();
        dao.close();
        isRun = false;
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
