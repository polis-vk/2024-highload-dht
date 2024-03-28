package ru.vk.itmo.test.emelyanovvitaliy;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class DhtService implements Service {
    private final ServiceConfig serviceConfig;
    private DhtServer server;

    public DhtService(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        server = new DhtServer(serviceConfig);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        if (server != null) {
            server.stop();
            server = null;
        }
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new DhtService(config);
        }
    }
}
