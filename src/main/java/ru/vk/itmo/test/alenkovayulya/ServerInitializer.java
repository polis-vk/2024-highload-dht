package ru.vk.itmo.test.alenkovayulya;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.alenkovayulya.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;

public final class ServerInitializer {
    public static final int PORT = 8080;
    public static final String URL = "http://localhost";

    private ServerInitializer() {
    }

    public static void main(String[] args) throws IOException {
        ServiceConfig config = new ServiceConfig(PORT, URL, List.of(URL),
                Files.createTempDirectory("reports")
        );
        ExecutorService executorService = ExecutorServiceFactory.getExecutorService(
                ExecutorServiceConfig.defaultConfig());
        ShardSelector shardSelector = new ShardSelector(config.clusterUrls());
        ReferenceDao dao = new ReferenceDao(new Config(config.workingDir(), 1024 * 1024 * 1024));
        ServerImpl server = new ServerImpl(config, dao, executorService, shardSelector);
        server.start();
    }

}
