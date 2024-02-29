package ru.vk.itmo.test.smirnovandrew;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class Main {
    public static void main(String[] args) throws IOException {
        Path data = Path.of("data");
        ReferenceDao dao = new ReferenceDao(
                new Config(
                        data,
                        2 * 1024 * 1024
                )
        );
        MyServer server = new MyServer(
                new ServiceConfig(
                        8080,
                        "http://localhost",
                        List.of("http://localhost"),
                        data
                ),
                dao
        );
        server.start();
    }

    private Main() {

    }
}
