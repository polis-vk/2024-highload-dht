package ru.vk.itmo.test.abramovilya;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class MainServer {
    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        ServiceConfig serviceConfig = new ServiceConfig(8080,
                "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory("."));
        Service service = new ru.vk.itmo.test.abramovilya.Service(serviceConfig);
        var future = service.start();
        future.get();
    }
}
