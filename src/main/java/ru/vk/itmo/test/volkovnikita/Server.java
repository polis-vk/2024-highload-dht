package ru.vk.itmo.test.volkovnikita;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Server {

    public static final String DIRECTORY_PREFIX = "tmp/";
    public static final int NODES = 5;
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    public static final long FLUSH_THRESHOLD_BYTES = 2 * 1024 * 1024L;

    private Server() {

    }

    public static void main(String[] args) {
        Map<Integer, String> nodes = new HashMap<>();
        int nodePort = 8080;
        for (int i = 0; i < NODES; i++) {
            nodes.put(nodePort, "http://localhost:" + nodePort);
            nodePort += 1;
        }

        nodes.forEach((port, url) -> {
            try {
                ServiceConfig config = new ServiceConfig(
                        port,
                        url,
                        new ArrayList<>(nodes.values()),
                        Files.createDirectories(Paths.get(DIRECTORY_PREFIX + port))
                );
                ServiceImpl server = new ServiceImpl(config);
                server.start().get(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException | IOException e) {
                log.error("Unable to initialize or start service instance: ", e);
                Thread.currentThread().interrupt();
            }
        });
    }
}
