package ru.vk.itmo.test.tarazanovmaxim;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class ServerMain {

     private ServerMain() {
    }

    public static void main(String[] args)
            throws IOException, ExecutionException, InterruptedException, URISyntaxException {
        List<MyService> services = new ArrayList<>();
        List<String> urls = List.of(
            "http://localhost:8080",
            "http://localhost:8081",
            "http://localhost:8082"
        );

        for (String url : urls) {
            int port = new URI(url).getPort();
            services.add(
                new MyService(
                    new ServiceConfig(
                        port,
                        "http://localhost:" + port,
                        urls,
                        Files.createTempDirectory("dao" + Integer.toString(port))
                    )
                )
            );
        }

        for (MyService service : services) {
            service.start().get();
        }
    }
}
