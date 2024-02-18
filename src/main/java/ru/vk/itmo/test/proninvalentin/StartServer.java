package ru.vk.itmo.test.proninvalentin;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class StartServer {
    public static void main(String[] args) throws IOException {
        String url = "http://localhost";
        int port = 8080;
        int flushThresholdBytes = 1 << 27; // 128 MB
        Path profilingDataPath = Path.of("/Users/valentinpronin/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/proninvalentin/server_profiling_data");
        Files.createDirectories(profilingDataPath);

        Config daoConfig = new Config(profilingDataPath, flushThresholdBytes);
        ServiceConfig serviceConfig = new ServiceConfig(port, url, List.of(url), profilingDataPath);
        ReferenceDao referenceDao = new ReferenceDao(daoConfig);

        Server server = new Server(serviceConfig, referenceDao);
        server.start();
    }
}
