package ru.vk.itmo.test.viktorkorotkikh;

import one.nio.async.CustomThreadFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.viktorkorotkikh.dao.LSMDaoImpl;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;

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
    private static final int SERVER_EXECUTOR_SERVICE_THREADS_COUNT = 16;
    private static final int SERVER_EXECUTOR_SERVICE_QUEUE_SIZE = 1024;
    private static final int CLUSTER_HTTP_CLIENT_EXECUTOR_SERVICE_THREADS_COUNT = 16;
    private static final int CLUSTER_HTTP_CLIENT_EXECUTOR_SERVICE_QUEUE_SIZE = 1024;
    private static final int CLUSTER_RESPONSE_EXECUTOR_SERVICE_THREADS_COUNT = 16;
    private static final int CLUSTER_RESPONSE_EXECUTOR_SERVICE_QUEUE_SIZE = 1024;
    private static final int LOCAL_REQUEST_EXECUTOR_SERVICE_THREADS_COUNT = 16;
    private static final int LOCAL_REQUEST_EXECUTOR_SERVICE_QUEUE_SIZE = 1024;
    private final ServiceConfig serviceConfig;
    private LSMServerImpl httpServer;
    private boolean isRunning;
    private Dao<MemorySegment, TimestampedEntry<MemorySegment>> dao;
    private ExecutorService executorService;
    private final ConsistentHashingManager consistentHashingManager;
    private HttpClient clusterClient;
    private ExecutorService clusterClientExecutorService;
    private ExecutorService clusterResponseProcessor;
    private ExecutorService localProcessor;

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
        Thread stopServiceHook = new Thread(() -> {
            try {
                lsmService.stop().join();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        Runtime.getRuntime().addShutdownHook(stopServiceHook);
    }

    public LSMServiceImpl(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
        this.consistentHashingManager = new ConsistentHashingManager(10, serviceConfig.clusterUrls());
    }

    private LSMServerImpl createServer(
            Dao<MemorySegment, TimestampedEntry<MemorySegment>> dao
    ) throws IOException {
        executorService = createExecutorService(
                SERVER_EXECUTOR_SERVICE_THREADS_COUNT,
                SERVER_EXECUTOR_SERVICE_QUEUE_SIZE,
                "worker"
        );
        clusterClientExecutorService = createExecutorService(
                CLUSTER_HTTP_CLIENT_EXECUTOR_SERVICE_THREADS_COUNT,
                CLUSTER_HTTP_CLIENT_EXECUTOR_SERVICE_QUEUE_SIZE,
                "cluster-request"
        );
        clusterResponseProcessor = createExecutorService(
                CLUSTER_RESPONSE_EXECUTOR_SERVICE_THREADS_COUNT,
                CLUSTER_RESPONSE_EXECUTOR_SERVICE_QUEUE_SIZE,
                "cluster-response-processor"
        );
        localProcessor = createExecutorService(
                LOCAL_REQUEST_EXECUTOR_SERVICE_THREADS_COUNT,
                LOCAL_REQUEST_EXECUTOR_SERVICE_QUEUE_SIZE,
                "local-processor"
        );

        clusterClient = HttpClient.newBuilder()
                .executor(clusterClientExecutorService)
                .build();

        return new LSMServerImpl(
                serviceConfig,
                dao,
                executorService,
                consistentHashingManager,
                clusterClient,
                clusterResponseProcessor,
                localProcessor
        );
    }

    private static Dao<MemorySegment, TimestampedEntry<MemorySegment>> createLSMDao(Path workingDir) {
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

    private static void closeLSMDao(Dao<MemorySegment, TimestampedEntry<MemorySegment>> dao) {
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

        httpServer = createServer(dao);
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
        shutdownExecutorService(clusterResponseProcessor);
        shutdownExecutorService(localProcessor);
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

    @ServiceFactory(stage = 6)
    public static class LSMServiceFactoryImpl implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new LSMServiceImpl(config);
        }
    }

}
