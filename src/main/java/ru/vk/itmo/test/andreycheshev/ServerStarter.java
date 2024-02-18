package ru.vk.itmo.test.andreycheshev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ServerStarter {
    public static void main(String[] args) throws IOException {
        Path dir = Path.of("/home/andrey/andrey/lab1_tmp");
        try {
            Files.createDirectory(dir);
        } catch (FileAlreadyExistsException e) {
            // it's ok.
        }

        ServerImpl server = new ServerImpl(
                new ServiceConfig(
                        8080,
                        "http://localhost",
                        List.of("http://localhost"),
                        dir
                ));

        server.start();
    }
}
