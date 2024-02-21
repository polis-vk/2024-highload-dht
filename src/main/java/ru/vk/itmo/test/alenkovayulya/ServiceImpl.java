package ru.vk.itmo.test.alenkovayulya;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.alenkovayulya.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private ReferenceDao referenceDao;
    private ServerImpl server;
    private final ServiceConfig config;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;

    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        referenceDao = new ReferenceDao(new Config(config.workingDir(), 1024 * 1024 * 1024));
        server = new ServerImpl(config, referenceDao);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        referenceDao.close();
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
