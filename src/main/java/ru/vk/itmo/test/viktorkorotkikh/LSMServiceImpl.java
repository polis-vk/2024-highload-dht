package ru.vk.itmo.test.viktorkorotkikh;

import one.nio.async.CustomThreadFactory;
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
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class LSMServiceImpl implements Service {
    private static final long FLUSH_THRESHOLD = 1 << 20; // 1 MB
    private static final int TERMINATION_TIMEOUT_SECONDS = 20;
    private final ServiceConfig serviceConfig;
    private LSMServerImpl httpServer;
    private boolean isRunning;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private ExecutorService executorService;
    private final ConsistentHashingManager consistentHashingManager;
    private HttpClient clusterClient;
    private ExecutorService clusterClientExecutorService;

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        Path baseWorkingDir = Path.of("daoWorkingDir");
        List<String> argList = Arrays.asList(args);
        int port = 8080;
        List<String> clusterUrls = new ArrayList<>();

        // just for local development
        int portArgIndex = argList.indexOf("--port");
        if (portArgIndex >= 0) {
            port = Integer.parseInt(argList.get(portArgIndex + 1));
        }

        int clusterUrlsIndex = argList.indexOf("--clusterUrls");
        if (clusterUrlsIndex >= 0) {
            clusterUrlsIndex++;
            while (clusterUrlsIndex < argList.size()) {
                String clusterUrl = argList.get(clusterUrlsIndex);
                if (Objects.equals(clusterUrl, "--workingDir")) {
                    break;
                }
                clusterUrls.add(clusterUrl);
                clusterUrlsIndex++;
            }
        }

        int workingDirIndex = argList.indexOf("--workingDir");
        Path workingDir;
        if (workingDirIndex >= 0) {
            workingDir = baseWorkingDir.resolve(argList.get(workingDirIndex + 1));
        } else {
            workingDir = baseWorkingDir.resolve(String.valueOf(port));
        }

        ServiceConfig serviceConfig = new ServiceConfig(
                port,
                "http://localhost:" + port,
                clusterUrls,
                workingDir
        );
        LSMServiceImpl lsmService = new LSMServiceImpl(serviceConfig);

        lsmService.start().get();
    }

    public LSMServiceImpl(ServiceConfig serviceConfig) throws IOException {
        this.serviceConfig = serviceConfig;
        this.consistentHashingManager = new ConsistentHashingManager(10, serviceConfig.clusterUrls());
    }

    private static LSMServerImpl createServer(
            ServiceConfig serviceConfig,
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            ExecutorService executorService,
            ConsistentHashingManager consistentHashingManager,
            HttpClient clusterClient
    ) throws IOException {
        return new LSMServerImpl(serviceConfig, dao, executorService, consistentHashingManager, clusterClient);
    }

    private static Dao<MemorySegment, Entry<MemorySegment>> createLSMDao(Path workingDir) {
        Config daoConfig = new Config(
                workingDir,
                FLUSH_THRESHOLD
        );
        return new LSMDaoImpl(daoConfig);
    }

    private static ExecutorService createExecutorService(
            final int workers,
            final int queueSize,
            final String threadsName
    ) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new CustomThreadFactory(threadsName, true),
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartAllCoreThreads();
        return executor;
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

        executorService = createExecutorService(16, 1024, "worker");
        clusterClientExecutorService = createExecutorService(16, 1024, "cluster-worker");

        clusterClient = HttpClient.newBuilder()
                .executor(clusterClientExecutorService)
                .build();

        httpServer = createServer(serviceConfig, dao, executorService, consistentHashingManager, clusterClient);
        httpServer.start();

        isRunning = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (!isRunning) return CompletableFuture.completedFuture(null);
        httpServer.stop();

        shutdownHttpClient(clusterClient);
        shutdownExecutorService(clusterClientExecutorService);
        shutdownExecutorService(executorService);
        executorService = null;
        clusterClient = null;
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

    private static void shutdownHttpClient(HttpClient httpClient) {
        httpClient.shutdown();
        try {
            httpClient.awaitTermination(Duration.of(TERMINATION_TIMEOUT_SECONDS, ChronoUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        httpClient.shutdownNow();
        try {
            httpClient.awaitTermination(Duration.of(TERMINATION_TIMEOUT_SECONDS, ChronoUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @ServiceFactory(stage = 3)
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
