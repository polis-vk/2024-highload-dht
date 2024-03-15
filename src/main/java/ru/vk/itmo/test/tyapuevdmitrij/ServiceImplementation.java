package ru.vk.itmo.test.tyapuevdmitrij;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImplementation implements ru.vk.itmo.Service {

    private ServerImplementation server;
    private final ServiceConfig config;

    public ServiceImplementation(ServiceConfig config) {
       this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        try {
            server = new ServerImplementation(config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        server.closeDAO();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public ru.vk.itmo.Service create(ServiceConfig config) {
            return new ServiceImplementation(config);
        }
    }
}
