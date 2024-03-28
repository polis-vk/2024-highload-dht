package ru.vk.itmo.test.pelogeikomakar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.pelogeikomakar.dao.ReferenceDaoPel;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceImpl implements Service {
    private final Config daoConfig;

    private final ServiceConfig serviceConfig;
    private DaoHttpServer server;
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    private static final Logger log = LoggerFactory.getLogger(ServiceImpl.class);

    public ServiceImpl(ServiceConfig config) {
        daoConfig = new Config(config.workingDir(), 1_048_576L);
        serviceConfig = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        isStopped.set(false);
        Dao<MemorySegment, Entry<MemorySegment>> dao = new ReferenceDaoPel(daoConfig);
        server = new DaoHttpServer(serviceConfig, dao, ExecutorServiceFactory.newExecutorService());
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        if (!isStopped.getAndSet(true)) {
            server.stop();
            shutdownAndAwaitTermination(server.getExecutorService());
            server.stopHttpClients();
            server.getDao().close();
        }
        return CompletableFuture.completedFuture(null);
    }

    private static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
          if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("Pool did not terminate");
            }
          }
        } catch (InterruptedException ex) {
          pool.shutdownNow();
          Thread.currentThread().interrupt();
        }
    }

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
