package ru.vk.itmo.test.khadyrovalmasgali.service;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.khadyrovalmasgali.server.DaoServer;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class DaoService implements Service {

    private DaoServer server;
    private final ServiceConfig config;

    public DaoService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        server = new DaoServer(config);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.shutdown();
        server.stop();
        return CompletableFuture.completedFuture(null);
    }
}
