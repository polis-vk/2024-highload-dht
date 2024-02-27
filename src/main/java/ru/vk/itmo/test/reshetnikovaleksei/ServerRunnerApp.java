package ru.vk.itmo.test.reshetnikovaleksei;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;

public final class ServerRunnerApp {
    private ServerRunnerApp() {

    }

    public static void main(String[] args) throws IOException {
        Path path = Path.of("/Users/alreshetnikov/IdeaProjects/2024-highload-dht/data");

        ServiceConfig serviceConfig = new ServiceConfig(8080, "http:localhost", List.of("http:localhost"), path);
        Dao<MemorySegment, Entry<MemorySegment>> dao = new ReferenceDao(new Config(path, 1024));
        HttpServerImpl server = new HttpServerImpl(serviceConfig, dao);

        server.start();
    }
}
