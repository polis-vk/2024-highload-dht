package ru.vk.itmo.test.kovalevigor.server.strategy;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface ServerStrategy extends AutoCloseable {

    Response handleRequest(Request request, HttpSession session) throws IOException;
    default CompletableFuture<Response> handleRequestAsync(Request request, HttpSession session) {
        return handleRequestAsync(request, session, null);
    }

    default CompletableFuture<Response> handleRequestAsync(
            Request request,
            HttpSession session,
            Executor executor
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return handleRequest(request, session);
            } catch (IOException e) {
                handleIOException(request, session, e);
            }
            return null;
        }, executor);
    }

    void handleDefault(Request request, HttpSession session) throws IOException;
    default CompletableFuture<Response> handleDefaultAsync(Request request, HttpSession session, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                handleDefault(request, session);
            } catch (IOException e) {
                handleIOException(request, session, e);
            }
            return null;
        }, executor);
    }

    void rejectRequest(Request request, HttpSession session);
    void handleIOException(Request request, HttpSession session, IOException exception);

    @Override
    void close() throws IOException;

    default void start(ServerFull httpServer) {
        // tut pusto, ofk
    }
}
