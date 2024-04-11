package ru.vk.itmo.test.timofeevkirill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.timofeevkirill.dao.Dao;
import ru.vk.itmo.test.timofeevkirill.dao.ReferenceDao;
import ru.vk.itmo.test.timofeevkirill.dao.TimestampEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ru.vk.itmo.test.timofeevkirill.Settings.FLUSH_THRESHOLD_BYTES;
import static ru.vk.itmo.test.timofeevkirill.Settings.getDefaultThreadPoolExecutor;

public class TimofeevService implements Service {
    private static final Logger logger = LoggerFactory.getLogger(TimofeevService.class);
    private final ServiceConfig config;
    private final Config daoConfig;
    private TimofeevServer server;
    private ThreadPoolExecutor threadPoolExecutor;
    private Dao<MemorySegment, TimestampEntry<MemorySegment>> dao;
    private TimofeevProxyService proxyService;

    public TimofeevService(ServiceConfig serviceConfig) {
        this.config = serviceConfig;
        this.daoConfig = new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES);
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        threadPoolExecutor = getDefaultThreadPoolExecutor();
        threadPoolExecutor.prestartAllCoreThreads();
        proxyService = new TimofeevProxyService(config);
        server = new TimofeevServer(config, dao, threadPoolExecutor, proxyService);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        server.stop();
        shutdownAndAwaitTermination(threadPoolExecutor);
        proxyService.close();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    logger.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig serviceConfig) {
            return new TimofeevService(serviceConfig);
        }
    }
}
