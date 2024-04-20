package ru.vk.itmo.test.bandurinvladislav;

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

public final class ServerLauncher {

    private ServerLauncher() {
    }

    public static void main(String[] args)
            throws IOException, ExecutionException, InterruptedException, TimeoutException {
        int[] nodePorts = new int[]{8080, 8090, 8100};
        Map<Integer, String> nodes = new HashMap<>();

        for (int nodePort : nodePorts) {
            nodes.put(nodePort, "http://localhost:" + nodePort);
        }

        List<String> clusterUrls = new ArrayList<>(nodes.values());
        List<ServiceConfig> clusterConfs = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : nodes.entrySet()) {
            int port = entry.getKey();
            String url = entry.getValue();
            Path path = Files.createTempDirectory("tmp-db-" + port);
            ServiceConfig serviceConfig = new ServiceConfig(port,
                    url,
                    clusterUrls,
                    path);
            clusterConfs.add(serviceConfig);
        }

        for (ServiceConfig serviceConfig : clusterConfs) {
            ServiceImpl instance = new ServiceImpl(serviceConfig);
            instance.start().get(1, TimeUnit.SECONDS);
        }
    }
}
