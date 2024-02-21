package ru.vk.itmo.test.tveritinalexandr;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Main {
    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
//        19234
        int port = 8080;
        String url = "http://localhost:" + port;
        ServiceConfig config= new ServiceConfig(
                port,
                url,
                Collections.singletonList(url),
                Files.createTempDirectory("data")
        );

        new ServiceImpl(config).start().get();
        System.out.println("Service is ready");
    }
}
