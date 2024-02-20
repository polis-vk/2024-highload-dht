package ru.vk.itmo.test.tyapuevdmitrij;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class ServerStarter {

    private ServerStarter() {

    }
    public static void main(String[] args) throws IOException {
        ServerImplementation server = new ServerImplementation(new ServiceConfig(8080,
                "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory(".")));
        server.start();
    }
}
