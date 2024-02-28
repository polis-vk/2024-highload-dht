package ru.vk.itmo.test.timofeevkirill;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.timofeevkirill.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static ru.vk.itmo.test.timofeevkirill.Settings.FLUSH_THRESHOLD_BYTES;
import static ru.vk.itmo.test.timofeevkirill.Settings.getDefaultThreadPoolExecutor;

public final class ApplicationServer {

    public static void main(String[] args) throws IOException {
        Path workingDir = Path.of("/home/aphirri/IdeaProjects/2024-highload-dht" +
                "/src/main/java/ru/vk/itmo/test/timofeevkirill/tmp");
        Files.createDirectories(workingDir);

        Config daoConfig = new Config(workingDir, FLUSH_THRESHOLD_BYTES);
        TimofeevServer server = new TimofeevServer(
                new ServiceConfig(
                        8080,
                        "http://localhost",
                        List.of("http://localhost"),
                        workingDir
                ),
                new ReferenceDao(daoConfig),
                getDefaultThreadPoolExecutor()
        );
        server.start();
    }

    private ApplicationServer() {
    }
}
