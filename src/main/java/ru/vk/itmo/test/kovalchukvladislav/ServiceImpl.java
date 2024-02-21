package ru.vk.itmo.test.kovalchukvladislav;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.kovalchukvladislav.server.DaoHttpRequestHandler;

public class ServiceImpl implements Service {
    private final ServiceConfig serviceConfig;
    private DaoHttpRequestHandler daoHttpRequestHandler;

    public ServiceImpl(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        this.daoHttpRequestHandler = new DaoHttpRequestHandler(serviceConfig);
        this.daoHttpRequestHandler.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        this.daoHttpRequestHandler.stop();
        return CompletableFuture.completedFuture(null);
    }
}
