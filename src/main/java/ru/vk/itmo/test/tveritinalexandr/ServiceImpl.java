package ru.vk.itmo.test.tveritinalexandr;

import one.nio.http.HttpServerConfig;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.tveritinalexandr.dao.DaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    private ServerImpl httpServer;
    private final ServiceConfig serviceConfig;

    public ServiceImpl(ServiceConfig serviceConfig) throws IOException {
        this.serviceConfig = serviceConfig;
    }

    private static HttpServerConfig adaptConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.reusePort = true;
        acceptorConfig.port = serviceConfig.selfPort();

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;

        return httpServerConfig;
    }

    @Override
    public CompletableFuture<Void> start() {
        try {
            httpServer = createServerInstance();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        httpServer.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        httpServer.stop();
        return CompletableFuture.completedFuture(null);
    }

    private ServerImpl createServerInstance() throws IOException {
        return new ServerImpl(
                adaptConfig(serviceConfig),
                new DaoImpl(new Config(serviceConfig.workingDir(), 10 * 1024 * 1024))
        );
    }

    @ServiceFactory(stage = 1)
    public static class FactoryImpl implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            try {
                return new ServiceImpl(config);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

}
