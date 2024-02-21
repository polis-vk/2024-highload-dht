package ru.vk.itmo.test.khadyrovalmasgali.service;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.khadyrovalmasgali.server.DaoServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class DaoService implements Service {

    private final DaoServer server;

    public DaoService(ServiceConfig config) {
        try {
            this.server = new DaoServer(config);
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
}
