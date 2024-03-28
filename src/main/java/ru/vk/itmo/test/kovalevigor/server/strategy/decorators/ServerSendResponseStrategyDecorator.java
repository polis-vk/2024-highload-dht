package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;

import java.io.IOException;

public class ServerSendResponseStrategyDecorator extends ServerStrategyDecorator {

    public ServerSendResponseStrategyDecorator(ServerStrategy httpServer) {
        super(httpServer);
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) throws IOException {
        Response response = super.handleRequest(request, session);
        if (response == null && Thread.currentThread().isInterrupted()) {
            session.sendError(Response.INTERNAL_ERROR, "");
        } else if (response == null) {
            session.sendError(Response.GATEWAY_TIMEOUT, "");
        } else {
            session.sendResponse(response);
        }
        return null;
    }
}
