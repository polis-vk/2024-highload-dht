package ru.vk.itmo.test.bandurinvladislav;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.bandurinvladislav.config.DhtServerConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ServerLauncher {

    private ServerLauncher() {
    }

    public static void main(String[] args) throws IOException {
        ServiceConfig serviceConfig = new ServiceConfig(
                8080, "http://localhost", List.of("http://localhost"),
                Path.of("/home/vbandurin/github/tmp/db"));
        DhtServerConfig serverConfig = ServiceImpl.createServerConfig(serviceConfig);
        Server server = new Server(serverConfig, serviceConfig.workingDir());
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
