package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpClient;
import one.nio.http.HttpServerConfig;
import one.nio.net.ConnectionString;
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
    private static final int THRESHOLD_BYTES = 1024 * 128; // 128 kb
    private static final int CLUSTER_NODE_RESPONSE_TIMEOUT_MILLIS = 1000;

    private final HttpServerConfig serverConfig;
    private final Config daoConfig;
    private final String selfUrl;
    private final List<String> sortedClusterUrls;
    private final RendezvousDistributor rendezvousDistributor;

    private HttpClient[] clusterConnections;
    private Server server;

    public ServiceImpl(ServiceConfig config) {
        this.sortedClusterUrls = new ArrayList<>(config.clusterUrls());
        Collections.sort(sortedClusterUrls);

        this.selfUrl = config.selfUrl();
        this.serverConfig = createServerConfig(config);
        this.daoConfig = new Config(config.workingDir(), THRESHOLD_BYTES);

        int thisNodeNumber = sortedClusterUrls.indexOf(selfUrl);
        this.rendezvousDistributor = new RendezvousDistributor(sortedClusterUrls.size(), thisNodeNumber);
    }

    private void initCluster(String selfUrl) {
        this.clusterConnections = new HttpClient[sortedClusterUrls.size()];

        int nodeNumber = 0;
        for (String serverUrl : sortedClusterUrls) {
            if (serverUrl.equals(selfUrl)) {
                nodeNumber++;
                continue;
            }

            HttpClient client = new HttpClient(new ConnectionString(serverUrl));
            client.setTimeout(CLUSTER_NODE_RESPONSE_TIMEOUT_MILLIS);

            clusterConnections[nodeNumber++] = client;
        }
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
            initCluster(selfUrl);

            PersistentReferenceDao dao = new PersistentReferenceDao(daoConfig);
            RequestHandler handler = new RequestHandler(dao, clusterConnections, rendezvousDistributor);
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

        for (HttpClient clusterConnection : clusterConnections) {
            if (clusterConnection != null && !clusterConnection.isClosed()) { // If true - this node array position.
                clusterConnection.close();
            }
        }

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
