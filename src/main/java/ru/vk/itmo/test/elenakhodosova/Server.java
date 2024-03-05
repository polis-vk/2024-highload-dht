package ru.vk.itmo.test.elenakhodosova;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class Server {
    public static final long FLUSH_THRESHOLD_BYTES = 4 * 1024 * 1024;

    private Server() {

    }

    public static void main(String[] args) throws IOException {
         ReferenceDao dao;
         ServiceConfig config = new ServiceConfig(
                8080,
                "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory(".")
        );

        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        HttpServerImpl server = new HttpServerImpl(config, dao);
        server.start();
    }
}
