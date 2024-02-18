package ru.vk.itmo.test.trofimovmaxim;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class RunServer {
    public static void main(String[] args) throws IOException {
        String url = "http://localhost";
        ServiceConfig config = new ServiceConfig(
                8080,
                url,
                List.of(url),
                Files.createTempDirectory("tmp")
        );
        TrofikServer server = new TrofikServer(config);
        server.start();
    }
}
