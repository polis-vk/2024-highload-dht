package ru.vk.itmo.test.osokindm;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.reference.ReferenceService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TestServer {

    private static final String LOCALHOST_PREFIX = "http://localhost:";

    private TestServer() {
    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
//        ServiceImpl service = new ServiceImpl(new ServiceConfig(
//                8080, "http://localhost",
//                List.of("http://localhost"),
//                Path.of("/home/john/database/")
//        ));
//        service.start().get();

        //port -> url
        Map<Integer, String> nodes = new HashMap<>();
        int nodePort = 8080;
        for (int i = 0; i < 3; i++) {
            nodes.put(nodePort, LOCALHOST_PREFIX + nodePort);
            nodePort += 10;
        }

        List<String> clusterUrls = new ArrayList<>(nodes.values());
        List<ServiceConfig> clusterConfs = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : nodes.entrySet()) {
            int port = entry.getKey();
            String url = entry.getValue();
            Path path = Paths.get("tmp/db/" + port);
            Files.createDirectories(path);
            ServiceConfig serviceConfig = new ServiceConfig(port,
                    url,
                    clusterUrls,
                    path);
            clusterConfs.add(serviceConfig);
        }

        for (ServiceConfig serviceConfig : clusterConfs) {
            ServiceImpl instance = new ServiceImpl(serviceConfig);
            try {
                instance.start().get(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
