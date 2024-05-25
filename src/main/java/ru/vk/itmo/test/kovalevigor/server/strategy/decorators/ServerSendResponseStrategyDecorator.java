package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.closeSession;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.sendErrorWithoutIo;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.sendResponseWithoutIo;

public class ServerSendResponseStrategyDecorator extends ServerStrategyDecorator {

    public ServerSendResponseStrategyDecorator(ServerStrategy httpServer) {
        super(httpServer);
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) {
        try {
            sendResponse(super.handleRequest(request, session), session);
        } catch (IOException e) {
            log.log(Level.SEVERE, "IO while executing", e);
            closeSession(session, e);
        }
        return null;
    }

    @Override
    public CompletableFuture<Response> handleRequestAsync(
            Request request,
            HttpSession session,
            Executor executor
    ) {
        return super.handleRequestAsync(request, session, executor)
                .whenCompleteAsync((response, exception) -> {
                    if (response == null && exception != null) {
                        if (exception instanceof TimeoutException) {
                            sendErrorWithoutIo(session, Response.GATEWAY_TIMEOUT, "");
                        } else {
                            log.log(Level.SEVERE, "IO exception", exception);
                            sendErrorWithoutIo(session, Response.INTERNAL_ERROR, "");
                        }
                    } else {
                        sendResponse(response, session);
                    }
                }, executor);
    }

    public void sendResponse(Response response, HttpSession session) {
        if (response == null && Thread.currentThread().isInterrupted()) {
            sendErrorWithoutIo(session, Response.INTERNAL_ERROR, "");
        } else if (response == null) {
            sendErrorWithoutIo(session, Response.GATEWAY_TIMEOUT, "");
        } else {
            sendResponseWithoutIo(session, response);
        }
    }
}
