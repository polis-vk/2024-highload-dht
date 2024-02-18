package ru.vk.itmo.test.osokindm;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class TestServer {
    public static void main(String[] args) throws IOException {
        HttpServerImpl server = new HttpServerImpl(new ServiceConfig(
                8080, "http://localhost",
                List.of("http://localhost"),
                Path.of("/home/john/database/")
        ));
        server.start();
    }
}
