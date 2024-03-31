package ru.vk.itmo.test.georgiidalbeev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class Server {

    private static final String DIRECTORY = "tmp/dao1";

    private Server() {

    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void main(String[] args) throws IOException {
        Path directoryPath = Paths.get(DIRECTORY);
        if (!Files.exists(directoryPath)) {
            Files.createDirectories(directoryPath);
        }

        NewService service = new NewService(
                new ServiceConfig(
                        8080,
                        "http://localhost:8080",
                        List.of("http://localhost:8080", "http://localhost:8081", "http://localhost:8082"),
                        directoryPath
                )
        );

        service.start();
    }
}
