package ru.vk.itmo.test.georgiidalbeev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class NewService implements ru.vk.itmo.Service {

    private NewServer server;

    private final ServiceConfig config;

    public NewService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        server = new NewServer(config);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        server.stop();
        server.terminate();
        return CompletableFuture.completedFuture(null);
    }
}
