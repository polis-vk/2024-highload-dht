package ru.vk.itmo.test.vadimershov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        List<Integer> ports = List.of(9080, 9081);
        List<String> clusterUrls = new ArrayList<>(ports.size());
        for (int port : ports) {
            clusterUrls.add("http://localhost:" + port);
        }
        for (int port : ports) {
            Path path = Path.of("/Users/ruarsv5/Developer/ITMO/sem-2/highload/src/test/dao_data/" + port);
            Files.createDirectories(path);
            ReferenceDao referenceDao = new ReferenceDao(new Config(path, 1 << 15));
            ServiceConfig serviceConfig = new ServiceConfig(port, "http://localhost:" + port, clusterUrls, path);
            DaoHttpServer server = new DaoHttpServer(serviceConfig, referenceDao, new RequestThreadExecutor.Config());
            server.start();
        }

    }
}
