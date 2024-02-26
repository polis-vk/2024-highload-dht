package ru.vk.itmo.test.reference;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class Server {

    public static void main(String[] args) throws IOException {
        ReferenceServer server = new ReferenceServer(new ServiceConfig(
            8080, "http://localhost",
            List.of("http://localhost"),
            Paths.get("tmp/db")
        ), new ReferenceDao(new Config(
            Paths.get("tmp/db"),
            1024 * 1024)
        ));
        server.start();
    }
}
