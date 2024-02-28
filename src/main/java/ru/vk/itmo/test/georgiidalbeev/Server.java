package ru.vk.itmo.test.georgiidalbeev;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.smirnovandrew.MyService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Server {
    private static final String DIR = "tmp/dao";

    private Server() {

    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void main(String[] args) throws IOException {
        Path path = Path.of(DIR);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        MyService service = new MyService(
                new ServiceConfig(
                        8080,
                        "http://localhost:8080",
                        List.of("http://localhost:8080"),
                        path
                )
        );

        service.start();
    }
}
