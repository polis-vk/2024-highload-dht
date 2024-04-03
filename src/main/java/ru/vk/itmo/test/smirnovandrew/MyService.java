package ru.vk.itmo.test.smirnovandrew;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class MyService implements Service {

    private static final int FLUSH_THRESHOLD_BYTES = 40 * 1024 * 1024;

    private ReferenceDao dao;

    private MyServer server;

    private final ServiceConfig config;

    private boolean isServerStopped;

    public MyService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        this.dao = new ReferenceDao(
                new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES)
        );
        this.server = new MyServer(config, dao);
        server.start();
        isServerStopped = false;

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        if (!isServerStopped) {
            server.stop();
        }
        isServerStopped = true;

        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return CompletableFuture.completedFuture(null);
    }
}
