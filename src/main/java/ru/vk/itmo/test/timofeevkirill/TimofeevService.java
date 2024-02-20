package ru.vk.itmo.test.timofeevkirill;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class TimofeevService implements Service {

    private final TimofeevServer server;

    public TimofeevService(ServiceConfig serviceConfig) {
        try {
            server = new TimofeevServer(serviceConfig);
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
        public Service create(ServiceConfig serviceConfig) {
            return new TimofeevService(serviceConfig);
        }
    }
}
