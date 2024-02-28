package ru.vk.itmo.test.andreycheshev;

import one.nio.http.*;
import ru.vk.itmo.dao.Config;

import java.io.IOException;
import java.io.UncheckedIOException;

public class ServerImpl extends HttpServer {
    private final RequestExecutor executor;

    public ServerImpl(HttpServerConfig config, Config daoConfig) throws IOException {
        super(config);

        this.executor = new RequestExecutor(daoConfig);
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
