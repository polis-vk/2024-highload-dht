package ru.vk.itmo.test.volkovnikita;

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

    public static final String DIRECTORY_DB_PREFIX = "tmp/db/";
    public static final int NODES = 3;
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    private static final String LOCALHOST_PREFIX = "http://localhost:";

    private Server() {

    }

    public static void main(String[] args) {
        Map<Integer, String> nodes = new HashMap<>();
        int nodePort = 8080;
        for (int i = 0; i < NODES; i++) {
            nodes.put(nodePort, LOCALHOST_PREFIX + nodePort);
            nodePort += 1;
        }

        nodes.forEach((port, url) -> {
            try {
                ServiceConfig config = new ServiceConfig(
                        port,
                        url,
                        new ArrayList<>(nodes.values()),
                        Files.createDirectories(Paths.get(DIRECTORY_DB_PREFIX + port))
                );
                ServiceImpl instance = new ServiceImpl(config);
                instance.start().get(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException | IOException e) {
                log.error("Unable to initialize or start service instance: ", e);
                Thread.currentThread().interrupt();
            }
        });
    }
}
