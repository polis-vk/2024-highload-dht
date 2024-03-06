package ru.vk.itmo.test.tuzikovalexandr;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class Server {

    private Server() {

    }

    public static void main(String[] args) throws IOException {
        long flushThresholdBytes = 1024 * 1024;
        Dao dao = new ReferenceDao(new Config(Files.createTempDirectory("data"), flushThresholdBytes));
        ServerImpl server = new ServerImpl(new ServiceConfig(
                8080, "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory("data")
        ), dao);
        server.start();
    }
}
