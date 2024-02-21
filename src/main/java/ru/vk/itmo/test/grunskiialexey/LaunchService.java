package ru.vk.itmo.test.grunskiialexey;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class LaunchService implements Service {
    private final DaoServer server;
    private boolean isStarted = false;

    public LaunchService(ServiceConfig config) {
        try {
            this.server = new DaoServer(config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /*
    There are will be code which execute server with some actions
     */
    @Override
    public CompletableFuture<Void> start() throws IOException {
        server.loadDao();
        if (!isStarted) {
            server.start();
            isStarted = true;
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.closeDao();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new LaunchService(config);
        }
    }
}
