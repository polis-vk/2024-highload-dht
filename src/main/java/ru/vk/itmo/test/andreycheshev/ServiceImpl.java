package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServerConfig;
import one.nio.server.AcceptorConfig;
import one.nio.server.Server;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    private static final int THRESHOLD_BYTES = 100000;

    private final HttpServerConfig serverConfig;
    private final Config daoConfig;

    private Server server;

    public ServiceImpl(ServiceConfig config) {
        this.serverConfig = createServerConfig(config);
        this.daoConfig = new Config(config.workingDir(), THRESHOLD_BYTES);
    }

    private HttpServerConfig createServerConfig(ServiceConfig config) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig newServerConfig = new HttpServerConfig();
        newServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        newServerConfig.closeSessions = true;

        return newServerConfig;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        try {
            server = new ServerImpl(serverConfig, daoConfig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
