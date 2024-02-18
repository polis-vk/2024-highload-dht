package ru.vk.itmo.test.andreycheshev;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    private final ServiceConfig serviceConfig;
    private ServerImpl server;
    private boolean isActive = false;

    public ServiceImpl(ServiceConfig config) {
        this.serviceConfig = config;
        initServer();
    }

    private void initServer() {
        try {
            server = new ServerImpl(serviceConfig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        isActive = true;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        if (!isActive) {
            initServer();
        }
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        isActive = false;
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
