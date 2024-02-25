package ru.vk.itmo.test.nikitaprokopev;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class ServerStarter {

    private static final String DIRECTORY = "tmp/dao";
    private static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024; // 1 MB

    private ServerStarter() {

    }

    public static void main(String[] args) throws IOException {
        Path directoryPath = Paths.get(DIRECTORY);
        if (!Files.exists(directoryPath)) {
            Files.createDirectories(directoryPath);
        }
        MyServer server = new MyServer(
                new ServiceConfig(
                        8080,
                        "http://localhost",
                        List.of("http://localhost"),
                        directoryPath
                ),
                new ReferenceDao(new Config(directoryPath, FLUSH_THRESHOLD_BYTES))
        );

        server.start();
    }
}
