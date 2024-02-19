package ru.vk.itmo.test.kachmareugene;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class Main {
    public static void main(String[] args) throws IOException {

        Server server = new Server(new ServiceConfig(
           8080, "http://localhost",
           List.of("http://localhost"),
                Files.createTempDirectory(".")
        ));
        server.start();
    }
}
