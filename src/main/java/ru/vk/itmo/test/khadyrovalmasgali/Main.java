package ru.vk.itmo.test.khadyrovalmasgali;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.khadyrovalmasgali.server.DaoServer;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class Main {
    public static void main(String[] args) throws IOException {
        String selfUrl = "http://localhost";
        DaoServer server = new DaoServer(new ServiceConfig(
                8080,
                selfUrl,
                List.of(selfUrl),
                Files.createTempDirectory(".")
        ));
        server.start();
    }

    private Main() {

    }
}
