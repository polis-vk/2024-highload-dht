package ru.vk.itmo.test.bandurinvladislav;

import one.nio.server.AcceptorConfig;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.bandurinvladislav.config.DhtServerConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    private final DhtServerConfig serverConfig;
    private final ServiceConfig config;
    private Server server;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
        this.serverConfig = createServerConfig(config);
    }

    @Override
    public CompletableFuture<Void> start() {
        try {
            server = new Server(serverConfig, config.workingDir());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        server.stop();
        return CompletableFuture.completedFuture(null);
    }

    public static DhtServerConfig createServerConfig(ServiceConfig serviceConfig) {
        DhtServerConfig serverConfig = new DhtServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        serverConfig.clusterUrls = serviceConfig.clusterUrls();
        serverConfig.selfUrl = serviceConfig.selfUrl();
        return serverConfig;
    }

    @ServiceFactory(stage = 3)
    public static class Factory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
