package ru.vk.itmo.test.tarazanovmaxim;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class MyService implements Service {
    private MyServer server;

    private final ServiceConfig config;

    private AtomicBoolean stopped;

    public MyService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        server = new MyServer(config);
        stopped = new AtomicBoolean(false);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        if (stopped.compareAndSet(false, true)) {
            server.stop();
        }

        return CompletableFuture.completedFuture(null);
    }
}
