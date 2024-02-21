package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public final class TestServer {

    private TestServer() {
    }

    public static void main(String[] args) throws IOException {
        String url = "http://localhost";
        ServiceConfig serviceConfig = new ServiceConfig(
                8080,
                url,
                List.of(url),
                Paths.get("./"));
        ReferenceDao dao = new ReferenceDao(
                new Config(serviceConfig.workingDir(), 1024 * 128));
        Server server = new Server(serviceConfig, dao);
        server.start();
    }
}
