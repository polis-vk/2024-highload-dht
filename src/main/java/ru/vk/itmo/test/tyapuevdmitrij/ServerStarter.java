package ru.vk.itmo.test.tyapuevdmitrij;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.tyapuevdmitrij.dao.MemorySegmentDao;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ServerStarter {
    private static final long FLUSH_THRESHOLD_BYTES = 1 << 20; // 1 MB

    private ServerStarter() {
    }

    public static void main(String[] args) throws IOException {
        Path tempPath = new File("/home/dmitrij/Документы/JavaProjects/DaoServerData/").toPath();
        ServerImplementation server = new ServerImplementation(new ServiceConfig(8080,
                "http://localhost",
                List.of("http://localhost"),
                tempPath), new MemorySegmentDao(new Config(tempPath, FLUSH_THRESHOLD_BYTES)));
        server.start();
    }
}
