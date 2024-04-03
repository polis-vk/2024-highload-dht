package ru.vk.itmo.test.alenkovayulya;

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

public final class ServerInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(ServerInitializer.class);
    public static final String LOCALHOST = "http://localhost:";
    public static final String DIRECTORY_PREFIX = "tmp/";
    public static final int SHARDS_COUNT = 3;

    private ServerInitializer() {
    }

    public static void main(String[] args) throws IOException {

        List<ServiceConfig> shardsConfigs = new ArrayList<>(SHARDS_COUNT);

        Map<Integer, String> shards = new HashMap<>();
        int shardPort = 8080;
        for (int i = 0; i < SHARDS_COUNT; i++) {
            shards.put(shardPort, LOCALHOST + shardPort);
            shardPort += 5;
        }

        for (Map.Entry<Integer, String> entry : shards.entrySet()) {
            final Integer port = entry.getKey();
            final String url = entry.getValue();
            ServiceConfig config = new ServiceConfig(
                    port,
                    url,
                    shards.values().stream().toList(),
                    Files.createDirectories(Paths.get(DIRECTORY_PREFIX + port))
            );
            shardsConfigs.add(config);
        }

        for (ServiceConfig config : shardsConfigs) {
            ServiceImpl server = new ServiceImpl(config);
            try {
                server.start().get(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                LOGGER.warn("Error during server starting: ", e);
                Thread.currentThread().interrupt();
            }
        }

    }

}
