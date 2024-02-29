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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LSMServiceImpl implements Service {
    private static final long FLUSH_THRESHOLD = 1 << 20; // 1 MB
    private static final int TERMINATION_TIMEOUT_SECONDS = 20;
    private final ServiceConfig serviceConfig;
    private LSMServerImpl httpServer;
    private boolean isRunning;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private ExecutorService executorService;

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        Path tmpDir = Files.createTempDirectory("dao");
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
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            ExecutorService executorService
    ) throws IOException {
        return new LSMServerImpl(serviceConfig, dao, executorService);
    }

    private static Dao<MemorySegment, Entry<MemorySegment>> createLSMDao(Path workingDir) {
        Config daoConfig = new Config(
                workingDir,
                FLUSH_THRESHOLD
        );
        return new LSMDaoImpl(daoConfig);
    }

    private static ExecutorService createExecutorService(final int workers, final int queueSize) {
        return new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new ThreadFactory() {
                    private final AtomicInteger threadNumber = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setName("worker #" + threadNumber.getAndIncrement());
                        return thread;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
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

        executorService = createExecutorService(16, 1024);

        httpServer = createServer(serviceConfig, dao, executorService);
        httpServer.start();

        isRunning = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (!isRunning) return CompletableFuture.completedFuture(null);
        httpServer.stop();

        shutdownExecutorService(executorService);
        executorService = null;
        httpServer = null;

        closeLSMDao(dao);
        dao = null;

        isRunning = false;
        return CompletableFuture.completedFuture(null);
    }

    private static void shutdownExecutorService(ExecutorService executorService) {
        executorService.shutdown();
        try {
            executorService.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executorService.shutdownNow();
        try {
            executorService.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @ServiceFactory(stage = 2)
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
