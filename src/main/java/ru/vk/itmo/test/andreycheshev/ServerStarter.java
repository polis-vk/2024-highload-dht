package ru.vk.itmo.test.andreycheshev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class ServerStarter {
    public static void main(String[] args) throws IOException {
        ServerImpl server = new ServerImpl(
                new ServiceConfig(
                        8080,
                        "http://localhost",
                        List.of("http://localhost"),
                        Path.of("/home/andrey/andrey/tmp")
                ));

        server.start();
    }
}
