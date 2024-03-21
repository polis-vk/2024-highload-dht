package ru.vk.itmo.test.kachmareugene;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class ServerActivator {
    private ServerActivator() {

    }

    public static void main(String[] args) throws IOException {
        ServiceConfig config1 = new ServiceConfig(
                8080, "http://localhost:8080/",
                List.of("http://localhost:8080/",
                        "http://localhost:8081/"),
                Files.createTempDirectory(".")
        );

        var m1 = new ServerManager(config1);

        ServiceConfig config2 = new ServiceConfig(
                8081, "http://localhost:8081/",
                List.of("http://localhost:8080/",
                        "http://localhost:8081/"),
                Files.createTempDirectory(".")
        );

        var m2 = new ServerManager(config2);

        m1.start();
        m2.start();

    }

    public static void oneNode() throws IOException {
        HttpServerImpl server = new HttpServerImpl(new ServiceConfig(
                8080, "http://localhost/",
                List.of("http://localhost/"),
                Files.createTempDirectory(".")
        ));
        server.start();
    }

}
