package ru.vk.itmo.test.asvistukhin;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.asvistukhin.dao.PersistentDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private final ServiceConfig serviceConfig;
    private ProxyRequestHandler proxyRequestHandler;
    private PersistentDao dao;
    private ServerImpl server;

    public ServiceImpl(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new PersistentDao(new Config(serviceConfig.workingDir(), 1024 * 1024 * 5L));
        proxyRequestHandler = new ProxyRequestHandler(serviceConfig);
        server = new ServerImpl(serviceConfig, dao, proxyRequestHandler);

        server.addRequestHandlers(this);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        proxyRequestHandler.close();
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
