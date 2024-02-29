package ru.vk.itmo.test.kachmareugene;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ServerManager implements Service {

    public HttpServerImpl server;
    private final ServiceConfig config;

    public ServerManager(ServiceConfig conf) {
        try {
            this.server = new HttpServerImpl(conf);
            this.config = conf;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        this.server = new HttpServerImpl(config);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();

        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 1)
    public static class ServerFactory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServerManager(config);
        }
    }
}
