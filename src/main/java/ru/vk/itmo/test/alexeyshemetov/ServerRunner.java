package ru.vk.itmo.test.alexeyshemetov;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ServerRunner {
    public static void main(String[] args) throws IOException {
        Path tempDirectory = Files.createTempDirectory("highload");
        System.out.println(tempDirectory);
        Server server = new Server(new ServiceConfig(
            8080, "http://localhost",
            List.of("http://localhost"),
            tempDirectory
        ));
        server.start();
    }
}
