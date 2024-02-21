package ru.vk.itmo.test.reference;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class Server {

    public static void main(String[] args) throws IOException {
        ReferenceServer server = new ReferenceServer(new ServiceConfig(
            8080, "http://localhost",
            List.of("http://localhost"),
            Files.createTempDirectory(".")
        ));
        server.start();
    }
}
