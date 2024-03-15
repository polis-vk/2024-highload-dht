package ru.vk.itmo.test.nikitaprokopev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ServerStarter {
    private static final String WORKING_DIR = "tmp/dao";

    private ServerStarter() {

    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void main(String[] args) throws IOException {
        Path path = Path.of(WORKING_DIR);
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
