package ru.vk.itmo.test.kachmareugene;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class ServerActivator {
    private ServerActivator() {

    }

    public static void main(String[] args) throws IOException {

        HttpServerImpl server = new HttpServerImpl(new ServiceConfig(
                8080, "http://localhost/",
                List.of("http://localhost/"),
                Files.createTempDirectory(".")
        ));
        server.start();
    }
}
