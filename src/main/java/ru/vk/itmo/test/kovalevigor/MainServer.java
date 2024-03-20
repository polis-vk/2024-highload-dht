package ru.vk.itmo.test.kovalevigor;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.kovalevigor.server.ServiceImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MainServer {

    private static final Path BASE_DIRECTORY = Path.of("./dao-out");
    private static final String BASE_URL = "http://localhost";

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException, TimeoutException {
        int clusterSize = 2;
        try {
            List<String> clusterUrls = new ArrayList<>(clusterSize);
            List<ServiceConfig> clusterConfigs = new ArrayList<>(clusterSize);
            for (int i = 1, port = 8080; i <= clusterSize; ++i, port += 10) {
                String selfUrl = getServerUrl(port);
                clusterUrls.add(selfUrl);
                clusterConfigs.add(
                        new ServiceConfig(
                                port,
                                selfUrl,
                                clusterUrls,
                                getServerDirectory(i)
                        )
                );
            }

            ServiceConfig config = clusterConfigs.getFirst();
            Files.createDirectories(config.workingDir());
            ServiceImpl server = new ServiceImpl(
                    config, ServiceImpl.FLUSH_THRESHOLD_BYTES / clusterSize
            );
            server.start().get(10, TimeUnit.SECONDS);

            System.out.println("MAIN STARTED");
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private static String getServerUrl(int port) {
        return BASE_URL + ":" + port;
    }

    private static Path getServerDirectory(int index) {
        return BASE_DIRECTORY.resolve("node" + index);
    }

}
