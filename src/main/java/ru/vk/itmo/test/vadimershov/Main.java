package ru.vk.itmo.test.vadimershov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Path daoDataPath = Path.of("/Users/ruarsv5/Developer/ITMO/sem-2/highload/src/test/dao_data");
        Files.createDirectories(daoDataPath);

        ReferenceDao referenceDao = new ReferenceDao(new Config(daoDataPath, 1 << 15));

        String url = "localhost";
        ServiceConfig serviceConfig = new ServiceConfig(8080, url, List.of(url), daoDataPath);

        DaoHttpServer server = new DaoHttpServer(serviceConfig, referenceDao);
        server.start();
    }
}
