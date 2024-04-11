package ru.vk.itmo.test.pelogeikomakar;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.pelogeikomakar.dao.ReferenceDaoPel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MainServ {

    private MainServ() {

    }

    public static void main(String[] args) throws IOException {
        Path basePath = Path.of("/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem_2/dao_content");

        List<Integer> ports = List.of(8070, 8080, 8090);
        List<String> clusterUrls = new ArrayList<>(3);
        for (int port : ports) {
            clusterUrls.add("http://localhost:" + port);
        }

        List<ServiceConfig> configs = new ArrayList<>(3);

        for (int i = 0; i < ports.size(); ++i) {
            Path daoPath = Files.createTempDirectory(basePath, "tmpServ_" + i);
            ServiceConfig serviceConfig = new ServiceConfig(ports.get(i), clusterUrls.get(i), clusterUrls, daoPath);
            configs.add(serviceConfig);
        }

        for (ServiceConfig config: configs) {
            Config daoConfig = new Config(config.workingDir(), 1_048_576L);
            DaoHttpServer server = new DaoHttpServer(config, new ReferenceDaoPel(daoConfig));
            server.start();
        }
    }
}
