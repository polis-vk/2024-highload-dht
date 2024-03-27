package ru.vk.itmo.test.tuzikovalexandr;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.tuzikovalexandr.dao.Dao;
import ru.vk.itmo.test.tuzikovalexandr.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static ru.vk.itmo.test.tuzikovalexandr.Constants.BASE_PORT;
import static ru.vk.itmo.test.tuzikovalexandr.Constants.BASE_URL;
import static ru.vk.itmo.test.tuzikovalexandr.Constants.FLUSHING_THRESHOLD_BYTES;
import static ru.vk.itmo.test.tuzikovalexandr.Constants.NUMBER_OF_VIRTUAL_NODES;


public final class Server {

    private Server() {

    }

    public static void main(String[] args) throws IOException {
        int clusterSize = 3;

        List<String> clusterUrls = new ArrayList<>();
        int tempPortValue;
        for (int i = 0; i < clusterSize; i++) {
            tempPortValue = BASE_PORT + i;
            clusterUrls.add(BASE_URL + ":" + tempPortValue);
        }

        Worker worker = new Worker(new WorkerConfig());

        for (int i = 0; i < clusterSize; i++) {
            Path dataPath = Files.createTempDirectory("data");

            Dao dao = new ReferenceDao(new Config(dataPath, FLUSHING_THRESHOLD_BYTES));

            ServiceConfig serviceConfig = new ServiceConfig(BASE_PORT + i,
                    clusterUrls.get(i), clusterUrls, dataPath);

            ConsistentHashing consistentHashing = new ConsistentHashing(clusterUrls, NUMBER_OF_VIRTUAL_NODES);

            ServerImpl server = new ServerImpl(serviceConfig, dao, worker, consistentHashing);

            server.start();
        }
    }
}
