package ru.vk.itmo.test.vadimershov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Start {

    private Start() {
    }

    public static void main(String[] args) throws IOException {
        List<Integer> ports = List.of(9080);
        List<String> clusterUrls = new ArrayList<>(ports.size());
        for (int port : ports) {
            clusterUrls.add("http://localhost:" + port);
        }
        for (int port : ports) {
            Path path = Path.of("/Users/ruarsv5/Developer/ITMO/temp/" + port);
            Files.createDirectories(path);
            Config daoConfig = new Config(path, 1 << 17);
            ServiceConfig serviceConfig = new ServiceConfig(port, "http://localhost:" + port, clusterUrls, path);
            DaoHttpServer server = new DaoHttpServer(serviceConfig, daoConfig, new RequestThreadExecutor.Config());
            server.start();
        }

    }
}
