package ru.vk.itmo.test.ryabovvadim;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ryabovvadim.server.SimpleServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class ServiceImpl implements Service {

    private final ServiceConfig serviceConfig;
    private SimpleServer server;

    public ServiceImpl(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }

    @Override
    public CompletableFuture<Void> start() {
        createServer();
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        server.stop();
        return CompletableFuture.completedFuture(null);
    }

    private void createServer() {
        try {
            server = new SimpleServer(serviceConfig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        ServiceConfig serviceConfig = new ServiceConfig(
                8088,
                "http://localhost:8088",
                Collections.emptyList(),
                Files.createTempDirectory(".")
        );

        Service service = new ServiceImpl(serviceConfig);
        service.start().get();
    }
}
