package ru.vk.itmo.test.tyapuevdmitrij;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.tyapuevdmitrij.dao.MemorySegmentDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ServerStarter {
    private static final long FLUSH_THRESHOLD_BYTES = (128 / 3) << 20; // 42 MB

    private ServerStarter() {
    }

    public static void main(String[] args) throws IOException {
        Path tempPath = Files.createTempDirectory("DaoServer");
        ServerImplementation server = new ServerImplementation(new ServiceConfig(8080,
                "http://localhost",
                List.of("http://localhost"),
                tempPath), new MemorySegmentDao(new Config(tempPath, FLUSH_THRESHOLD_BYTES)));
        server.start();
    }
}
