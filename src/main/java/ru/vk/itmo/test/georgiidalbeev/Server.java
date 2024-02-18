package ru.vk.itmo.test.georgiidalbeev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class Server {

    public static void main(String[] args) throws IOException {
        NewServer server = new NewServer(new ServiceConfig(
                8080,
                "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory("."))
        );
        server.start();
    }
}
