package ru.vk.itmo.test.kachmareugene;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServerManager implements Service {

    public HttpServerImpl server;
    private final ServiceConfig config;
    private final PartitionMetaInfo info;

    public ServerManager(ServiceConfig conf) {
            info = new PartitionMetaInfo(conf.clusterUrls(), 3);
            this.config = conf;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {

        server = new HttpServerImpl(config, info);
        server.start();

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();

        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 3)
    public static class ServerFactory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new ServerManager(config);
        }
    }
}
