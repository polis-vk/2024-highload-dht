package ru.vk.itmo.test.volkovnikita;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class Server {

    private Server() {

    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void main(String[] args) throws IOException {
        ServiceConfig config = new ServiceConfig(
                8080,
                "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory(".")
        );
        ServiceImpl server = new ServiceImpl(config);
        server.start();
    }
}
