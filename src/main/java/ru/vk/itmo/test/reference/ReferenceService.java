package ru.vk.itmo.test;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ReferenceService implements Service {

    private final ReferenceServer server;

    public ReferenceService(ServiceConfig config) {
        try {
            server = new ReferenceServer(config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
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
            return new ReferenceService(config);
        }
    }
}
