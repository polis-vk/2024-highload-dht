package ru.vk.itmo.test.ryabovvadim;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ryabovvadim.server.Server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

//MY NOTE: think about name
public class ServiceImpl implements Service {

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        System.out.println(InetAddress.getLocalHost().getHostAddress());
        ServiceConfig serviceConfig = new ServiceConfig(
                8088,
                "http://localhost:8088",
                Collections.emptyList(),
                Files.createTempDirectory(".")
        );

        Service service = new ServiceImpl(serviceConfig);
        service.start().get();
    }

    private final Server server;

    public ServiceImpl(ServiceConfig serviceConfig) {
        try {
            this.server = new Server(serviceConfig);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public CompletableFuture<Void> start() {
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        server.stop();
        return CompletableFuture.completedFuture(null);
    }
}
