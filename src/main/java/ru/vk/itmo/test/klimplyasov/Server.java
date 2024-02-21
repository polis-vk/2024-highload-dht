package ru.vk.itmo.test.klimplyasov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class Server {

    public static void main(String[] args) throws IOException {
        ServiceConfig serviceConfig = new ServiceConfig(
                8080,
                "localhost",
                List.of("localhost"),
                Files.createTempDirectory(".")
        );
        ReferenceDao dao = new ReferenceDao(new Config(serviceConfig.workingDir(), 48000));
        PlyasovServer server = new PlyasovServer(serviceConfig, dao);
        server.start();
    }
}
