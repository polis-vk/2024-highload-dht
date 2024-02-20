package ru.vk.itmo.test.pelogeikomakar;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MainServ {

    private MainServ() {

    }
    
    public static void main(String[] args) throws IOException {
        Path basePath = Path.of("/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem 2/dao_content");
        Path daoPath = Files.createTempDirectory(basePath, "tmpServ");

        ServiceConfig serviceConfig = new ServiceConfig(8080, "http://localhost", List.of("http://localhost"), daoPath);
        Config daoConfig = new Config(daoPath, 2048L);

        DaoHttpServer server = new DaoHttpServer(serviceConfig, daoConfig);

        server.start();
    }
}
