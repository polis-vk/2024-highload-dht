package ru.vk.itmo.test.elenakhodosova;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public final class Server {

    public static final long FLUSH_THRESHOLD_BYTES = 4 * 1024 * 1024;
    public static final String LOCALHOST = "http://localhost:";
    public static final String DIRECTORY_PREFIX = "tmp/";
    public static final int NODES_COUNT = 3;

    private Server() {

    }

    public static void main(String[] args) throws IOException {
        ExecutorService executorService = ExecutorServiceConfig.getExecutorService();
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
                    nodes.values().stream().filter(value -> !Objects.equals(value, url)).collect(Collectors.toList()),
                    Files.createDirectories(Paths.get(DIRECTORY_PREFIX + url))
            );
            nodesConfigs.add(config);
        }
        for (ServiceConfig config : nodesConfigs) {
            ReferenceDao dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
            HttpServerImpl server = new HttpServerImpl(config, dao, executorService);
            server.start();
        }
    }
}
