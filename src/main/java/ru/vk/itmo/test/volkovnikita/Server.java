package ru.vk.itmo.test.volkovnikita;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class Server {

    public static final long FLUSH_THRESHOLD_BYTES = 2 * 1024 * 1024L;

    private Server() {

    }

    public static void main(String[] args) throws IOException {
        ServiceConfig config = new ServiceConfig(
                8080,
                "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory(".")
        );
        ReferenceDao dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        HttpServerImpl server = new HttpServerImpl(config, dao);
        server.start();
    }
}
