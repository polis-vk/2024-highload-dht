package ru.vk.itmo.test.kachmareugene;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class ServerManager implements Service {

    public HttpServerImpl server;
    private final ServiceConfig config;
    private final PartitionMetaInfo info;
    private HttpClient client;

    public ServerManager(ServiceConfig conf) {
            info = new PartitionMetaInfo(conf.clusterUrls(), 3);
            this.config = conf;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Utils.TIMEOUT_SECONDS))
                .build();

        server = new HttpServerImpl(config, info, client);
        server.start();

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        client.close();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 5)
    public static class ServerFactory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new ServerManager(config);
        }
    }
}
