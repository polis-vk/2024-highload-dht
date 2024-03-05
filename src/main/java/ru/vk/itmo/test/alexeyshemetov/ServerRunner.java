package ru.vk.itmo.test.alexeyshemetov;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class ServerRunner {
    private ServerRunner() {
    }
    public static void main(String[] args) throws IOException {
        Path dir = Path.of("/Users/alexshemetov/Desktop/highload/data/");
        System.out.println(dir);
        Server server = new Server(new ServiceConfig(
            8080, "http://localhost",
            List.of("http://localhost"),
            dir
        ));
        server.start();
    }
}
