package ru.vk.itmo.test.andreycheshev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class ServerStarter {
    public static void main(String[] args) throws IOException {
        ServerImpl server = new ServerImpl(
                new ServiceConfig(
                        80,
                        "http://localhost",
                        List.of("http://localhost"),
                        Files.createTempDirectory("/home/andrey/andrey/tmp")
                ));

        server.start();
    }
}
