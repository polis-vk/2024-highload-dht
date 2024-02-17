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
        String profilingDataPath = "/var/folders/ws/d96mcphn30qbsqmksq2td7v40000gn/T/server_profiling_data";

        Config daoConfig = new Config(Files.createTempDirectory("dao"), flushThresholdBytes);
        Server server = new Server(new ServiceConfig(
                port, url,
                List.of(url),
                Path.of(profilingDataPath)),
                new ReferenceDao(daoConfig));
        server.start();
    }
}
