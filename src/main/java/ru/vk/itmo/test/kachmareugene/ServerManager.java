package ru.vk.itmo.test.kachmareugene;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ServerManager implements Service {

    public List<HttpServerImpl> servers = new ArrayList<>();
    private final ServiceConfig config;
    private final PartitionMetaInfo info;

    public ServerManager(ServiceConfig conf) {

        try {
            int serversCount = conf.clusterUrls().size();
            info = new PartitionMetaInfo(conf.clusterUrls(), 3);

            for (int i = 0; i < serversCount; i++) {
                this.servers.add(new HttpServerImpl(conf, info, i));
            }

            this.config = conf;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {

        for (int i = 0; i < servers.size(); i++) {
            servers.set(i,new HttpServerImpl(config, info, i));
            servers.get(i).start();
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        for (var server : servers) {
            server.stop();
        }

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
