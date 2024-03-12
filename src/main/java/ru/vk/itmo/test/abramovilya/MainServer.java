package ru.vk.itmo.test.abramovilya;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class MainServer {
    private MainServer() {
    }

    public static void main(String[] args) throws Exception {
        List<String> serviceUrls = List.of(
                "http://localhost:8080",
                "http://localhost:8081"
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
