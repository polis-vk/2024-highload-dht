package ru.vk.itmo.test.kovalevigor.server.strategy;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;

import java.io.IOException;

public interface ServerStrategy extends AutoCloseable {

    Response handleRequest(Request request, HttpSession session) throws IOException;

    void handleDefault(Request request, HttpSession session) throws IOException;

    void rejectRequest(Request request, HttpSession session);

    @Override
    void close() throws IOException;

    default void start(ServerFull httpServer) {
        // tut pusto, ofk
    }
}
