package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServerConfig;
import one.nio.server.AcceptorConfig;
import one.nio.server.Server;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.andreycheshev.dao.PersistentReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    private static final int THRESHOLD_BYTES = 1024 * 1024;

    private final HttpServerConfig serverConfig;
    private final Config daoConfig;
    private final RendezvousDistributor rendezvousDistributor;

    private Server server;

    public ServiceImpl(ServiceConfig config) {
        this.serverConfig = createServerConfig(config);
        this.daoConfig = new Config(config.workingDir(), THRESHOLD_BYTES);

        List<String> sortedClusterUrls = new ArrayList<>(config.clusterUrls());
        Collections.sort(sortedClusterUrls);

        int thisNodeNumber = sortedClusterUrls.indexOf(config.selfUrl());
        this.rendezvousDistributor = new RendezvousDistributor(sortedClusterUrls, thisNodeNumber);
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
            PersistentReferenceDao dao = new PersistentReferenceDao(daoConfig);
            RequestHandler handler = new RequestHandler(dao, rendezvousDistributor);
            RequestExecutor executor = new RequestExecutor(handler);

            server = new ServerImpl(serverConfig, dao, executor);
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

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
