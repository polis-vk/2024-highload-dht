package ru.vk.itmo.test.trofimovmaxim;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class TrofikService implements Service {
    private TrofikServer server;
    private final ServiceConfig config;
    private boolean stopped;

    public TrofikService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        server = new TrofikServer(config);
        server.start();
        stopped = false;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        if (!stopped) {
            server.stop();
        }
        stopped = true;
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 3)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new TrofikService(config);
        }
    }
}
