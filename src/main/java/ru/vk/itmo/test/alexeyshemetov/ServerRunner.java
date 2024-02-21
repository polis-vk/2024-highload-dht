package ru.vk.itmo.test.alexeyshemetov;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final public class ServerRunner {
    private ServerRunner() {}
    public static void main(String[] args) throws IOException {
        Path tempDirectory = Files.createTempDirectory("highload");
        Server server = new Server(new ServiceConfig(
            8080, "http://localhost",
            List.of("http://localhost"),
            tempDirectory
        ));
        server.start();
    }
}
