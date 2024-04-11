package ru.vk.itmo.test.elenakhodosova;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Server {

    public static final long FLUSH_THRESHOLD_BYTES = 4 * 1024 * 1024;
    public static final String LOCALHOST = "http://localhost:";
    public static final String DIRECTORY_PREFIX = "tmp/";
    public static final int NODES_COUNT = 3;
    private static final Logger logger = LoggerFactory.getLogger(HttpServerImpl.class);

    private Server() {

    }

    public static void main(String[] args) throws IOException {
        List<ServiceConfig> nodesConfigs = new ArrayList<>(NODES_COUNT);

        Map<Integer, String> nodes = new HashMap<>();
        int nodePort = 8080;
        for (int i = 0; i < NODES_COUNT; i++) {
            nodes.put(nodePort, LOCALHOST + nodePort);
            nodePort += 5;
        }

        for (Map.Entry<Integer, String> entry : nodes.entrySet()) {
            final Integer port = entry.getKey();
            final String url = entry.getValue();
            ServiceConfig config = new ServiceConfig(
                    port,
                    url,
                    nodes.values().stream().toList(),
                    Files.createDirectories(Paths.get(DIRECTORY_PREFIX + port))
            );
            nodesConfigs.add(config);
        }
        for (ServiceConfig config : nodesConfigs) {
            ServiceImpl server = new ServiceImpl(config);
            try {
                server.start().get(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                logger.error("Unable to start service instance: ", e);
                Thread.currentThread().interrupt();
            }
        }
    }
}
