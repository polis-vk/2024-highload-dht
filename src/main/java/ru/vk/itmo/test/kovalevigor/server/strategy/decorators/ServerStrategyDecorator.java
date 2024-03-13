package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;

import java.io.IOException;

public class ServerStrategyDecorator implements ServerStrategy {

    private final ServerStrategy serverStrategy;

    public ServerStrategyDecorator(ServerStrategy httpServer) {
        this.serverStrategy = httpServer;
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
    public void rejectRequest(Request request, HttpSession session) {
        serverStrategy.rejectRequest(request, session);
    }

    @Override
    public void close() throws IOException {
        serverStrategy.close();
    }
}
