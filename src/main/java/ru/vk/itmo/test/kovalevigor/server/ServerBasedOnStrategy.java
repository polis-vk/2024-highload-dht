package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.server.SelectorThread;

import java.io.IOException;

public class ServerBasedOnStrategy extends HttpServer implements ServerFull {

    private final ServerStrategy serverStrategy;

    public ServerBasedOnStrategy(HttpServerConfig config, ServerStrategy serverStrategy) throws IOException {
        super(config);
        this.serverStrategy = serverStrategy;
    }

    @Override
    public synchronized void start() {
        super.start();
        serverStrategy.start(this);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        serverStrategy.handleRequest(request, session);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        serverStrategy.handleDefault(request, session);
    }

    @Override
    public void close() throws IOException {
        serverStrategy.close();
    }

    @Override
    public SelectorThread[] getSelectors() {
        return selectors;
    }
}
