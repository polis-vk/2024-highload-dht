package ru.vk.itmo.test.timofeevkirill;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.timofeevkirill.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static ru.vk.itmo.test.timofeevkirill.Settings.FLUSH_THRESHOLD_BYTES;

public final class ApplicationServer {

    public static void main(String[] args) throws IOException {
        Config daoConfig = new Config(Files.createTempDirectory("dao"), FLUSH_THRESHOLD_BYTES);
        TimofeevServer server = new TimofeevServer(
                new ServiceConfig(
                        8080,
                        "http://localhost",
                        List.of("http://localhost"),
                        Files.createTempDirectory(".")
                ),
                new ReferenceDao(daoConfig)
        );
        server.start();
    }

    private ApplicationServer() {
    }
}
