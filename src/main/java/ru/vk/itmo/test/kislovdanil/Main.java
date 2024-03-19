package ru.vk.itmo.test.kislovdanil;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.kislovdanil.service.DatabaseServiceFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class Main {
    private Main() {

    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        DatabaseServiceFactory factory = new DatabaseServiceFactory();
        List<String> nodes = List.of("http://localhost:8080",
                "http://localhost:8081");
        ServiceConfig config = getConfig(args, nodes);
        Service service = factory.create(config);
        service.start().get();
    }

    private static ServiceConfig getConfig(String[] args, List<String> nodes) {
        int port;
        try {
            port = Integer.parseInt(args[0]);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new InputValidationError("Please, set port for your service");
        } catch (IllegalArgumentException e) {
            throw new InputValidationError("Port have to be an integer from 0 to 65535");
        }
        return new ServiceConfig(port, "http://localhost:" + port, nodes,
                Path.of("/home/burprop/Study/2024-highload-dht").resolve(String.valueOf(port)));
    }
}
