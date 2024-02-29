package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import ru.vk.itmo.dao.*;
import ru.vk.itmo.test.andreycheshev.dao.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.*;

public class ServerImpl extends HttpServer {
    private final RequestExecutor executor;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public ServerImpl(HttpServerConfig config, Config daoConfig) throws IOException {
        super(config);

        this.dao = new PersistentReferenceDao(daoConfig);
        this.executor = new RequestExecutor(new RequestHandler(dao));
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        executor.execute(request, session);
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            executor.shutdown();
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
