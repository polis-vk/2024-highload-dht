package ru.vk.itmo.test.kovalevigor.server;

import one.nio.server.AcceptorConfig;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.kovalevigor.config.DaoServerConfig;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private static final long FLUSH_THRESHOLD_BYTES = 128 * 1024 * 1024 / 3 * 8;
    private ServerFull server;
    public final DaoServerConfig config;

    public ServiceImpl(ServiceConfig config) {
        this.config = mapConfig(config);
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        server = new ServerBasedOnStrategy(
                config,
                new ServerOneExecutorStrategyDecorator(
                        new ServerDaoStrategy(config),
                        config.corePoolSize, config.maximumPoolSize,
                        config.keepAliveTime, config.queueCapacity
                )
        );
        return CompletableFuture.runAsync(server::start);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        if (server != null) {
            server.stop();
            server.close();
        }
        return CompletableFuture.completedFuture(null);
    }

    private static AcceptorConfig[] mapAcceptors(ServiceConfig config) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;
        return new AcceptorConfig[]{acceptorConfig};
    }

    private static DaoServerConfig mapConfig(ServiceConfig config) {
        DaoServerConfig serverConfig = new DaoServerConfig();
        serverConfig.basePath = config.workingDir();
        serverConfig.flushThresholdBytes = FLUSH_THRESHOLD_BYTES;
        serverConfig.acceptors = mapAcceptors(config);
        serverConfig.closeSessions = true;

        return serverConfig;
    }
}
