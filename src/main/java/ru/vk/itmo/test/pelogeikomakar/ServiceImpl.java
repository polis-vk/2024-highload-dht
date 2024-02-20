package ru.vk.itmo.test.pelogeikomakar;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    private final Config daoConfig;
    private final ServiceConfig serviceConfig;
    private HttpServer server;


    public ServiceImpl(ServiceConfig config) {
        daoConfig = new Config(config.workingDir(), 2048L);
        serviceConfig = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {

        server = new HttpServer(serviceConfig, daoConfig);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
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
