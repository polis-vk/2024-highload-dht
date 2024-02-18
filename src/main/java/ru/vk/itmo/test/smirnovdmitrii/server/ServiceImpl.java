package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServer;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private final ServiceConfig config;
    private HttpServer server;

    private static final String ADDRESS = "localhost";

    public ServiceImpl(final ServiceConfig config) throws IOException {
        this.config = config;
    }

    private static DaoHttpServerConfig createDaoHttpServerConfig(final ServiceConfig config) {
        final DaoHttpServerConfig serverConfig = new DaoHttpServerConfig();
        final AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.address = ADDRESS;
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        serverConfig.workingDir = config.workingDir();

        return serverConfig;
    }

    @Override
    public CompletableFuture<Void> start() {
        try {
            server = new Server(createDaoHttpServerConfig(config));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return CompletableFuture.runAsync(server::start);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(server::stop);
    }
}
