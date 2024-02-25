package ru.vk.itmo.test.viktorkorotkikh;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.viktorkorotkikh.dao.LSMDaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class LSMServiceImpl implements Service {
    private static final long FLUSH_THRESHOLD = 1 << 20; // 1 MB
    private final ServiceConfig serviceConfig;
    private LSMServerImpl httpServer;
    private boolean isRunning = false;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;

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
        this.serviceConfig = serviceConfig;
    }

    private static LSMServerImpl createServer(
            ServiceConfig serviceConfig,
            Dao<MemorySegment, Entry<MemorySegment>> dao
    ) throws IOException {
        return new LSMServerImpl(serviceConfig, dao);
    }

    private static Dao<MemorySegment, Entry<MemorySegment>> createLSMDao(Path workingDir) {
        Config daoConfig = new Config(
                workingDir,
                FLUSH_THRESHOLD
        );
        return new LSMDaoImpl(daoConfig);
    }

    private static void closeLSMDao(Dao<MemorySegment, Entry<MemorySegment>> dao) {
        if (dao == null) return;
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        if (isRunning) return CompletableFuture.completedFuture(null);
        dao = createLSMDao(serviceConfig.workingDir());

        httpServer = createServer(serviceConfig, dao);
        httpServer.start();

        isRunning = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (!isRunning) return CompletableFuture.completedFuture(null);
        httpServer.stop();
        httpServer = null;

        closeLSMDao(dao);
        dao = null;

        isRunning = false;
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
