package ru.vk.itmo.test.pavelemelyanov;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.pavelemelyanov.dao.Dao;
import ru.vk.itmo.test.pavelemelyanov.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static ru.vk.itmo.test.pavelemelyanov.HTTPUtils.NUMBER_OF_VIRTUAL_NODES;

public class ServiceImpl implements Service {
    private static final int FLUSH_THRESHOLD_BYTES = 1024 * 1024;

    private final ServiceConfig config;
    private MyServer server;
    private Dao dao;
    private ExecutorServiceWrapper worker;
    private Boolean isClosed = false;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        worker = new ExecutorServiceWrapper();
        ConsistentHashing consistentHashing = new ConsistentHashing(config.clusterUrls(), NUMBER_OF_VIRTUAL_NODES);
        server = new MyServer(config, dao, worker, consistentHashing);
        server.start();
        isClosed = false;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (isClosed) {
            return CompletableFuture.completedFuture(null);
        }
        server.stop();
        worker.shutdownAndAwaitTermination();
        dao.close();
        isClosed = true;
        return CompletableFuture.completedFuture(null);
    }
}
