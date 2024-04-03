package ru.vk.itmo.test.kachmareugene;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.dariasupriadkina.TestServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ServerActivator {
    private ServerActivator() {

    }

    public static void main(String[] args) throws IOException {
        String host1 = "http://localhost:8080/";
        String host2 = "http://localhost:8081/";
        ServiceConfig config1 = new ServiceConfig(
                8080, host1,
                List.of(host1, host2),
                Files.createTempDirectory(".")
        );

        var m1 = new ServerManager(config1);

        ServiceConfig config2 = new ServiceConfig(
                8081, host2,
                List.of(host1, host2),
                Files.createTempDirectory(".")
        );

        var m2 = new ServerManager(config2);

        CompletableFuture<Void> start1 = m1.start();
        CompletableFuture<Void> start2 = m2.start();

        try {
            start1.get();
            start2.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Logger logger = Logger.getAnonymousLogger();
            logger.log(Level.WARNING, e.toString());
        }

    }
}
