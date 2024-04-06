package ru.vk.itmo.test.abramovilya;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class MainServer {
    private MainServer() {
    }

    public static void main(String[] args)
            throws URISyntaxException, IOException, ExecutionException, InterruptedException {
        List<String> serviceUrls = List.of(
                "http://localhost:8080",
                "http://localhost:8081",
                "http://localhost:8082"
        );

        List<Service> serviceList = new ArrayList<>();
        for (String serviceUrl : serviceUrls) {
            URI uri = new URI(serviceUrl);
            int port = uri.getPort();
            ServiceConfig serviceConfig = new ServiceConfig(
                    port,
                    serviceUrl,
                    serviceUrls,
                    Files.createTempDirectory(".").resolve(String.valueOf(port))
            );
            Service service = new ServiceImpl(serviceConfig);
            serviceList.add(service);
        }

        for (Service service : serviceList) {
            service.start().get();
        }
    }
}
