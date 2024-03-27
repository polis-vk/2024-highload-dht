package ru.vk.itmo.test.tuzikovalexandr;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Server {
    private static String BASE_URL = "http://localhost";
    private static int BASE_PORT = 8080;

    private Server() {

    }

    public static void main(String[] args) throws IOException {
        int clusterSize = 3;
        long flushThresholdBytes = 1024 * 1024;

        List<String> clusterUrls = new ArrayList<>();
        int tempPortValue;
        for (int i = 0; i < clusterSize; i++) {
            tempPortValue = BASE_PORT + i;
            clusterUrls.add(BASE_URL + ":" + tempPortValue);
        }

        Worker worker = new Worker(new WorkerConfig());

        for (int i = 0; i < clusterSize; i++) {
            Path dataPath = Files.createTempDirectory("data");

            Dao dao = new ReferenceDao(new Config(dataPath, flushThresholdBytes));

            ServiceConfig serviceConfig = new ServiceConfig(BASE_PORT + i,
                    clusterUrls.get(i), clusterUrls, dataPath);

            ConsistentHashing consistentHashing = new ConsistentHashing(clusterUrls, 5);

            ServerImpl server = new ServerImpl(serviceConfig, dao, worker, consistentHashing);

            server.start();
        }
    }
}
