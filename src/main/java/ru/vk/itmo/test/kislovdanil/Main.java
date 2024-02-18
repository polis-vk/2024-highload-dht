package ru.vk.itmo.test.kislovdanil;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.kislovdanil.service.DatabaseServiceFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class Main {
    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        DatabaseServiceFactory factory = new DatabaseServiceFactory();
        ServiceConfig config = new ServiceConfig(8080, "localhost", List.of(),
                Path.of("/home/burprop/Study/2024-highload-dht"));
        Service service = factory.create(config);
        service.start().get();

    }
}
