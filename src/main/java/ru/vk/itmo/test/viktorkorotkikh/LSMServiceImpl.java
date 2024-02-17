package ru.vk.itmo.test.viktorkorotkikh;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class LSMServiceImpl implements Service {
    private final ServiceConfig serviceConfig;
    private LSMServerImpl httpServer;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        java.nio.file.Path tmpDir = Files.createTempDirectory("dao");
        tmpDir.toFile().deleteOnExit();

        ServiceConfig serviceConfig = new ServiceConfig(
                8080,
                "http://localhost:8080",
                List.of("http://localhost:8080"),
                tmpDir
        );
        LSMServiceImpl lsmService = new LSMServiceImpl(serviceConfig);

        lsmService.start().get();
    }

    public LSMServiceImpl(ServiceConfig serviceConfig) throws IOException {
        this.httpServer = createServer(serviceConfig);
        this.serviceConfig = serviceConfig;
    }

    private static LSMServerImpl createServer(ServiceConfig serviceConfig) throws IOException {
        return new LSMServerImpl(serviceConfig);
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        if (isRunning.getAndSet(true)) return CompletableFuture.completedFuture(null);
        if (httpServer == null) {
            httpServer = createServer(serviceConfig);
        }
        httpServer.startServer();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        if (!isRunning.getAndSet(false)) return CompletableFuture.completedFuture(null);
        httpServer.stopServer();
        httpServer = null;
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 1)
    public static class LSMServiceFactoryImpl implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            try {
                return new LSMServiceImpl(config);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

}
