package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ServerStrategyDecorator implements ServerStrategy {

    private final ServerStrategy serverStrategy;

    public ServerStrategyDecorator(ServerStrategy httpServer) {
        this.serverStrategy = httpServer;
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) throws IOException {
        return serverStrategy.handleRequest(request, session);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        serverStrategy.handleDefault(request, session);
    }

    @Override
    public CompletableFuture<Response> handleRequestAsync(Request request, HttpSession session) {
        return serverStrategy.handleRequestAsync(request, session);
    }

    @Override
    public CompletableFuture<Response> handleRequestAsync(Request request, HttpSession session, Executor executor) {
        return serverStrategy.handleRequestAsync(request, session, executor);
    }

    @Override
    public void rejectRequest(Request request, HttpSession session) {
        serverStrategy.rejectRequest(request, session);
    }

    @Override
    public void handleIOException(Request request, HttpSession session, IOException exception) {
        serverStrategy.handleIOException(request, session, exception);
    }

    @Override
    public void close() throws IOException {
        serverStrategy.close();
    }
}
