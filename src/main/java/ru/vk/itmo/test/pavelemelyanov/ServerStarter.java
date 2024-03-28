package ru.vk.itmo.test.pavelemelyanov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ServerStarter {
    private static final Path WORKING_DIR = Path.of("./data1/");
    private static final String URL = "http://localhost";
    private static final int FLUSH_THRESHOLD_BYTES = 2 * 1024 * 1024;

    public static void main(String[] args) throws IOException {
        ReferenceDao dao = new ReferenceDao(
                new Config(
                        WORKING_DIR,
                        FLUSH_THRESHOLD_BYTES
                )
        );
        MyServer server = new MyServer(
                new ServiceConfig(
                        8080,
                        URL,
                        List.of(URL),
                        WORKING_DIR
                ),
                dao
        );
        server.start();
    }

    private ServerStarter() {

    }
}
