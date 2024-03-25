package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServer;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtProperties;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtValue;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.PropertyException;
import ru.vk.itmo.test.smirnovdmitrii.dao.TimeEntry;
import ru.vk.itmo.test.smirnovdmitrii.dao.DaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class DaoServiceImpl implements Service {

    // acceptors
    @DhtValue("server.acceptor.threads")
    private static int acceptorThreads;
    @DhtValue("server.acceptor.reusePort")
    private static boolean reusePort;
    // dao
    @DhtValue("flush.threshold.megabytes")
    private static int flushThresholdMegabytes;
    // workers
    @DhtValue("server.workers.min")
    private static int minWorkers;
    @DhtValue("server.workers.max")
    private static int maxWorkers;
    @DhtValue("server.workers.queueTimeMs")
    private static int queueTime;
    @DhtValue("server.workers.keepAliveTime")
    private static int keepAliveTime;
    @DhtValue("server.workers.useWorkers")
    private static boolean useWorkers;
    @DhtValue("server.workers.queueSize")
    private static int queueSize;
    @DhtValue("server.workers.queueType")
    private static WorkerQueueType workerQueueType;
    // selectors
    @DhtValue("server.selectors.closeSessions")
    private static boolean closeSessions;
    @DhtValue("server.selectors.threads")
    private static int selectorThreads;
    @DhtValue("server.selectors.affinity")
    private static boolean selectorAffinity;
    @DhtValue("server.virtualThreads.enable")
    private static boolean useVirtualThreads;

    private final ServiceConfig config;
    private HttpServer server;

    public DaoServiceImpl(final ServiceConfig config) throws IOException {
        this.config = config;
    }

    private static DaoHttpServerConfig createDaoHttpServerConfig(final ServiceConfig config) {
        final DaoHttpServerConfig serverConfig = new DaoHttpServerConfig();
        // acceptors
        final AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = reusePort;
        acceptorConfig.threads = acceptorThreads;
        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        // selectors
        serverConfig.closeSessions = closeSessions;
        serverConfig.selectors = selectorThreads;
        serverConfig.affinity = selectorAffinity;
        // workers
        serverConfig.minWorkers = minWorkers;
        serverConfig.maxWorkers = maxWorkers;
        serverConfig.queueTime = queueTime;
        serverConfig.useWorkers = useWorkers;
        serverConfig.queueSize = queueSize;
        serverConfig.keepAliveTime = keepAliveTime;
        serverConfig.workerQueueType = workerQueueType;
        // virtual threads
        serverConfig.useVirtualThreads = useVirtualThreads;

        serverConfig.clusterUrls = config.clusterUrls();
        serverConfig.workingDir = config.workingDir();
        serverConfig.selfUrl = config.selfUrl();
        return serverConfig;
    }

    @Override
    public CompletableFuture<Void> start() {
        try {
            final Dao<MemorySegment, TimeEntry<MemorySegment>> dao = createDao(config.workingDir());
            server = new DaoHttpServer(createDaoHttpServerConfig(config), dao);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return CompletableFuture.runAsync(server::start);
    }

    private Dao<MemorySegment, TimeEntry<MemorySegment>> createDao(final Path basePath) {
        final long thresholdBytes = flushThresholdMegabytes * 1024L * 1024L;
        return new DaoImpl(new Config(basePath, thresholdBytes));
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(server::stop);
    }

    // Don't blame me! Blame the lack of configs!
    static {
        try {
            DhtProperties.initProperties();
        } catch (final ClassNotFoundException e) {
            throw new PropertyException(e);
        }
    }
}
