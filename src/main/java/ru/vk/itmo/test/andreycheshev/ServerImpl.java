package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;

import java.io.IOException;
import java.io.UncheckedIOException;

public class ServerImpl extends HttpServer {
    private static final int THRESHOLD_BYTES = 100000;
    private final RequestExecutor executor;

    public ServerImpl(ServiceConfig config) throws IOException {
        super(createServerConfig(config));

        Config daoConfig = new Config(config.workingDir(), THRESHOLD_BYTES);

        this.executor = new RequestExecutor(daoConfig);
    }

    public static HttpServerConfig createServerConfig(ServiceConfig config) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;

        return serverConfig;
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
