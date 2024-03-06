package ru.vk.itmo.test.pavelemelyanov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ServerStarter {
    public static void main(String[] args) throws IOException {
        ReferenceDao dao = new ReferenceDao(
                new Config(
                        Path.of("./data1/"),
                        2 * 1024 * 1024
                )
        );
        MyServer server = new MyServer(
                new ServiceConfig(
                        8080,
                        "http://localhost",
                        List.of("http://localhost"),
                        Path.of("./data1/")
                ),
                dao
        );
        server.start();
    }

    private ServerStarter() {

    }
}
