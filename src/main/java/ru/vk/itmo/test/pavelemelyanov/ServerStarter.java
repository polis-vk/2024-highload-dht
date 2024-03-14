package ru.vk.itmo.test.pavelemelyanov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.smirnovandrew.MyService;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class ServerStarter {
    private static final Path WORKING_DIR = Path.of("./data1/");
    private static final String URL = "http://localhost";
    private static final List<String> URLS = List.of(
            "http://localhost:8080",
            "http://localhost:8081",
            "http://localhost:8082"
    );

    public static void main(String[] args)
            throws IOException, URISyntaxException, ExecutionException, InterruptedException {
        List<MyService> services = new ArrayList<>();
        for (String url : URLS) {
            int port = new URI(url).getPort();
            services.add(
                    new MyService(
                            new ServiceConfig(
                                    port,
                                    URL,
                                    URLS,
                                    WORKING_DIR
                            )
                    )
            );
        }

        for (MyService service : services) {
            service.start().get();
        }
    }

    private ServerStarter() {

    }
}
