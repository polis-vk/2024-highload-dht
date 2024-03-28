package ru.vk.itmo.test.pavelemelyanov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.pavelemelyanov.dao.Dao;
import ru.vk.itmo.test.pavelemelyanov.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static ru.vk.itmo.test.pavelemelyanov.HTTPUtils.NUMBER_OF_VIRTUAL_NODES;

public final class ServerStarter {
    private static final String URL = "http://localhost";
    private static final Path WORKING_DIR = Path.of("./data1/");
    public static final long FLUSHING_THRESHOLD_BYTES = 1024 * 1024;
    private static final int BASE_PORT = 8080;

    public static void main(String[] args) throws IOException {
        int clusterSize = 3;

        List<String> clusterUrls = new ArrayList<>();
        for (int i = 0; i < clusterSize; i++) {
            int tempPortValue = BASE_PORT + i;
            clusterUrls.add(URL + ":" + tempPortValue);
        }

        ExecutorServiceWrapper worker = new ExecutorServiceWrapper();

        for (int i = 0; i < clusterSize; i++) {
            Dao dao = new ReferenceDao(new Config(WORKING_DIR, FLUSHING_THRESHOLD_BYTES));

            ServiceConfig serviceConfig = new ServiceConfig(
                    BASE_PORT + i,
                    clusterUrls.get(i),
                    clusterUrls,
                    WORKING_DIR);

            ConsistentHashing consistentHashing = new ConsistentHashing(clusterUrls, NUMBER_OF_VIRTUAL_NODES);

            MyServer server = new MyServer(serviceConfig, dao, worker, consistentHashing);

            server.start();
        }
    }

    private ServerStarter() {

    }
}
