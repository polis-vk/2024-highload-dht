package ru.vk.itmo.test.bazhenovkirill;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Server {

    public static void main(String[] args) throws IOException {
        ServerImpl server = new ServerImpl(new ServiceConfig(
                8080,
                "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory(".")
        ));

        server.start();
    }
}
