package ru.vk.itmo.test.kovalevigor.server;

import one.nio.server.AcceptorConfig;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.kovalevigor.config.DaoServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class ServiceImpl implements Service {

    private final static long FLUSH_THRESHOLD_BYTES = 128 * 1024 * 1024 / 3;
    private Server server;
    public final DaoServerConfig config;

    public ServiceImpl(ServiceConfig config) {
        this.config = mapConfig(config);
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        if (server == null) {
            server = new Server(config);
        }
        return CompletableFuture.runAsync(server::start);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        server.close();
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
