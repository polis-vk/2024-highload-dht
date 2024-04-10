package ru.vk.itmo.test.kovalevigor.server.strategy;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.util.Responses;

import java.io.IOException;

import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.sendResponseWithoutIo;

public class ServerRejectStrategy implements ServerStrategy {
    @Override
    public Response handleRequest(Request request, HttpSession session) throws IOException {
        rejectRequest(request, session);
        return null;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(Responses.BAD_REQUEST.toResponse());
    }

    @Override
    public void rejectRequest(Request request, HttpSession session) {
        sendResponseWithoutIo(session, Responses.SERVICE_UNAVAILABLE);
    }

    @Override
    public void handleIOException(Request request, HttpSession session, IOException exception) {
        // do nothing
    }

    @Override
    public void close() throws IOException {
        // nothing to close
    }
}
