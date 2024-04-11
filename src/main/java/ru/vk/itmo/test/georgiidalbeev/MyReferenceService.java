package ru.vk.itmo.test.georgiidalbeev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.georgiidalbeev.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MyReferenceService implements Service {
    private static final Logger log = LoggerFactory.getLogger(MyReferenceService.class);

    private static final long FLUSHING_THRESHOLD_BYTES = 1024 * 1024;

    private static final String LOCALHOST_PREFIX = "http://localhost:";

    private final ServiceConfig config;

    private ReferenceDao dao;
    private MyReferenceServer server;
    private boolean stopped;

    public MyReferenceService(ServiceConfig config) {
        this.config = config;
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws IOException {
        Map<Integer, String> nodes = new HashMap<>();
        int nodePort = 8080;
        for (int i = 0; i < 3; i++) {
            nodes.put(nodePort, LOCALHOST_PREFIX + nodePort);
            nodePort += 10;
        }

        List<String> clusterUrls = new ArrayList<>(nodes.values());
        List<ServiceConfig> clusterConfs = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : nodes.entrySet()) {
            int port = entry.getKey();
            String url = entry.getValue();
            Path path = Paths.get("tmp/db/" + port);
            Files.createDirectories(path);
            ServiceConfig serviceConfig = new ServiceConfig(port,
                    url,
                    clusterUrls,
                    path);
            clusterConfs.add(serviceConfig);
        }

        for (ServiceConfig serviceConfig : clusterConfs) {
            MyReferenceService instance = new MyReferenceService(serviceConfig);
            try {
                instance.start().get(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted exception while start instance");
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSHING_THRESHOLD_BYTES));
        server = new MyReferenceServer(config, dao);
        server.start();
        stopped = false;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (stopped) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            server.stop();

        } finally {
            dao.close();
        }
        stopped = true;
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 5)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new MyReferenceService(config);
        }
    }
}
