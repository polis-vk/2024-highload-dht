package ru.vk.itmo.test.tveritinalexandr;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.ExecutionException;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {

        int port = 8080;
        String url = "http://localhost:" + port;
        ServiceConfig config = new ServiceConfig(
                port,
                url,
                Collections.singletonList(url),
                Files.createTempDirectory("data")
        );

        new ServiceImpl(config).start().get();
    }
}
