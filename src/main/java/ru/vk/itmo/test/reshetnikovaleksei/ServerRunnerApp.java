package ru.vk.itmo.test.reshetnikovaleksei;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ServerRunnerApp {
    private static final int NODES_COUNT = 3;
    private static final String LOCALHOST_PREFIX = "http://localhost:";
    private static final String PATH_PREFIX = "/Users/alreshetnikov/data_%s/";

    private ServerRunnerApp() {

    }

    public static void main(String[] args) throws IOException {
        Map<Integer, String> nodes = generateNodes();

        List<ServiceConfig> configs = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : nodes.entrySet()) {
            Path path = Path.of(PATH_PREFIX.formatted(entry.getKey()));
            Files.createDirectories(path);

            ServiceConfig serviceConfig = new ServiceConfig(
                    entry.getKey(), entry.getValue(), new ArrayList<>(nodes.values()), path);
            configs.add(serviceConfig);

        }

        for (ServiceConfig config : configs) {
            try {
                ServiceImpl service = new ServiceImpl(config);
                service.start().get(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException();
            }
        }
    }

    private static Map<Integer, String> generateNodes() {
        Map<Integer, String> result = new HashMap<>();

        int nodePort = 8080;
        for (int i = 0; i < NODES_COUNT; i++) {
            result.put(nodePort, LOCALHOST_PREFIX + nodePort);
            nodePort += 10;
        }

        return result;
    }
}
