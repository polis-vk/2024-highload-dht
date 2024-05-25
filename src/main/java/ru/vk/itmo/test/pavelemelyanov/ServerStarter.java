package ru.vk.itmo.test.pavelemelyanov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.pavelemelyanov.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static ru.vk.itmo.test.pavelemelyanov.HttpUtils.NUMBER_OF_VIRTUAL_NODES;

public final class ServerStarter {
    private static final String URL = "http://localhost";
    public static final long FLUSHING_THRESHOLD_BYTES = 1024 * 1024;
    private static final int BASE_PORT = 8080;
    private static final int CLUSTER_SIZE = 3;

    public static void main(String[] args) throws IOException {
        List<String> clusterUrls = new ArrayList<>();
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            int tempPortValue = BASE_PORT + i;
            clusterUrls.add(URL + ":" + tempPortValue);
        }

        ExecutorServiceWrapper worker = new ExecutorServiceWrapper();

        for (int i = 0; i < CLUSTER_SIZE; i++) {
            Path data = Files.createTempDirectory("data12");

            var dao = new ReferenceDao(new Config(data, FLUSHING_THRESHOLD_BYTES));

            ServiceConfig serviceConfig = new ServiceConfig(
                    BASE_PORT + i,
                    clusterUrls.get(i),
                    clusterUrls,
                    data
            );

            ConsistentHashing consistentHashing = new ConsistentHashing(clusterUrls, NUMBER_OF_VIRTUAL_NODES);

            MyServer server = new MyServer(serviceConfig, dao, worker, consistentHashing);

            server.start();
        }
    }

    private ServerStarter() {

    }
}
