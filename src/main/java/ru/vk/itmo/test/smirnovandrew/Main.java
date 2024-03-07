package ru.vk.itmo.test.smirnovandrew;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class Main {
    public static void main(String[] args) throws IOException {
        Path data = Path.of("data");
        ReferenceDao dao = new ReferenceDao(
                new Config(
                        data,
                        2 * 1024 * 1024
                )
        );
        ServiceConfig serviceConfig = new ServiceConfig(
                8080,
                "http://localhost",
                List.of("http://localhost"),
                data
        );
        MyServer myServer;

        if (Objects.isNull(args) || args.length < 2) {
            myServer = new MyServer(serviceConfig, dao);
        } else {
            int corePoolSize = Integer.parseInt(args[0]);
            int availableProcessors = Integer.parseInt(args[1]);
            myServer = new MyServer(serviceConfig, dao, corePoolSize, availableProcessors);
        }
        myServer.start();
    }

    private Main() {

    }
}
