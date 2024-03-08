package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServerConfig;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import one.nio.server.Server;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.andreycheshev.dao.PersistentReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    private static final int THRESHOLD_BYTES = 1024 * 128; // 128 kb
    private static final int RESPONSE_AWAIT_TIMEOUT_MILLIS = 3000;

    private final HttpServerConfig serverConfig;
    private final Config daoConfig;
    private final Map<Integer, HttpClient> clusterConnections;
    private final DataDistributor dataDistributor;

    private Server server;

    public ServiceImpl(ServiceConfig config) {
        this.clusterConnections = getClusterConnections(config.clusterUrls(), config.selfUrl());
        this.serverConfig = createServerConfig(config);
        this.daoConfig = new Config(config.workingDir(), THRESHOLD_BYTES);
        this.dataDistributor = new DataDistributor(clusterConnections.size(), config.selfUrl());
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

    private Map<Integer, HttpClient> getClusterConnections(List<String> clusterServers, String selfUrl) {
        Collections.sort(clusterServers);

        Map<Integer, HttpClient> connections = new HashMap<>(clusterServers.size());
        int clientNumber = 1;
        for (String serverUrl : clusterServers) {
            if (serverUrl.equals(selfUrl)) {
                continue;
            }

            HttpClient client = new HttpClient(
                    new ConnectionString(serverUrl)
            );
            client.setTimeout(RESPONSE_AWAIT_TIMEOUT_MILLIS);

            try {
                client.connect(serverUrl);
            } catch (InterruptedException | PoolException | IOException | HttpException e) {
                throw new RuntimeException(e);
            }

            connections.put(clientNumber++, client);
        }

        return connections;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        try {
            PersistentReferenceDao dao = new PersistentReferenceDao(daoConfig);
            RequestHandler handler = new RequestHandler(dao, clusterConnections, dataDistributor);
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

    @ServiceFactory(stage = 3)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
