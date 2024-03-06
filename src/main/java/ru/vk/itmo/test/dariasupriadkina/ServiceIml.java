package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.dariasupriadkina.dao.exception.ServiceImplCreationException;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;

public class ServiceIml implements Service {

    private Server server;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private Config daoConfig;
    private ServiceConfig serviceConfig;

    public ServiceIml(ServiceConfig serviceConfig, Config daoConfig) {
        try {

            this.daoConfig = daoConfig;
            this.serviceConfig = serviceConfig;

            this.dao = new ReferenceDao(daoConfig);

        } catch (IOException e) {
            throw new ServiceImplCreationException(e);
        }
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        server = new Server(serviceConfig, dao);

        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        server.stop();
        dao.close();

        return CompletableFuture.completedFuture(null);
    }
}
