package ru.vk.itmo.test.tuzikovalexandr;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.tuzikovalexandr.dao.Dao;
import ru.vk.itmo.test.tuzikovalexandr.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static ru.vk.itmo.test.tuzikovalexandr.Constants.FLUSHING_THRESHOLD_BYTES;
import static ru.vk.itmo.test.tuzikovalexandr.Constants.NUMBER_OF_VIRTUAL_NODES;

public class ServiceImpl implements Service {

    private final ServiceConfig config;
    private ServerImpl server;
    private Dao dao;
    private Worker worker;
    private Boolean isClosed = false;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSHING_THRESHOLD_BYTES));
        worker = new Worker(new WorkerConfig());
        ConsistentHashing consistentHashing = new ConsistentHashing(config.clusterUrls(), NUMBER_OF_VIRTUAL_NODES);
        server = new ServerImpl(config, dao, worker, consistentHashing);
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

    @ServiceFactory(stage = 5)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
