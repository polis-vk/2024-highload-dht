package ru.vk.itmo.test.pelogeikomakar;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.pelogeikomakar.dao.ReferenceDaoPel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;

public final class MainServ {

    private MainServ() {

    }

    public static void main(String[] args) throws IOException {
        Path basePath = Path.of("/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem_2/dao_content");
        Path daoPath = Files.createTempDirectory(basePath, "tmpServ");

        ServiceConfig serviceConfig = new ServiceConfig(8080, "http://localhost", List.of("http://localhost"), daoPath);
        Config daoConfig = new Config(daoPath, 160_384L);

        ExecutorService execServ = ExecutorServiceFactory.newExecutorService();

        DaoHttpServer server = new DaoHttpServer(serviceConfig, new ReferenceDaoPel(daoConfig), execServ);

        server.start();
    }
}
