package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServer;
import one.nio.os.SchedulingPolicy;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtProperties;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.PropertyException;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.PropertyValue;
import ru.vk.itmo.test.smirnovdmitrii.dao.DaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class DaoServiceImpl implements Service {

    // acceptors
    @PropertyValue("server.acceptor.threads")
    private static int acceptorThreads;
    @PropertyValue("server.acceptor.reusePort")
    private static boolean reusePort;
    // dao
    @PropertyValue("flush.threshold.megabytes")
    private static int flushThresholdMegabytes;
    // workers
    @PropertyValue("server.workers.min")
    private static int minWorkers;
    @PropertyValue("server.workers.max")
    private static int maxWorkers;
    @PropertyValue("server.workers.queueTime")
    private static int queueTime;
    @PropertyValue("server.workers.schedulingPolicy")
    private static SchedulingPolicy schedulingPolicy;
    // selectors
    @PropertyValue("server.selectors.closeSessions")
    private static boolean closeSessions;
    @PropertyValue("server.selectors.threads")
    private static int selectorThreads;
    @PropertyValue("server.selectors.affinity")
    private static boolean selectorAffinity;

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
        serverConfig.closeSessions = closeSessions;
        // selectors
        serverConfig.selectors = selectorThreads;
        serverConfig.affinity = selectorAffinity;
        // workers
        serverConfig.minWorkers = minWorkers;
        serverConfig.maxWorkers = maxWorkers;
        serverConfig.queueTime = queueTime;
        serverConfig.schedulingPolicy = schedulingPolicy;

        serverConfig.workingDir = config.workingDir();
        return serverConfig;
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                final Dao<MemorySegment, Entry<MemorySegment>> dao = createDao(config.workingDir());
                server = new DaoHttpServer(createDaoHttpServerConfig(config), dao);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private Dao<MemorySegment, Entry<MemorySegment>> createDao(final Path basePath) {
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
