package ru.vk.itmo.test.tarazanovmaxim;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class MyService implements Service {
    private MyServer server;

    private final ServiceConfig config;

    public MyService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        server = new MyServer(config);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        server.close();
        return CompletableFuture.completedFuture(null);
    }
}
