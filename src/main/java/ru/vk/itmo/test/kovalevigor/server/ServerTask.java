package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpSession;
import one.nio.http.Request;

import java.io.IOException;

import static ru.vk.itmo.test.kovalevigor.server.ServerUtil.closeSession;
import static ru.vk.itmo.test.kovalevigor.server.ServerUtil.sendResponseWithoutIo;
import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;

public class ServerTask implements Runnable {
    public final Request request;
    public final HttpSession session;
    public final IOExceptionBiConsumer<Request, HttpSession> task;

    @FunctionalInterface
    public interface IOExceptionBiConsumer<T, U> {
        void accept(T t, U u) throws IOException;
    }

    public ServerTask(Request request, HttpSession session, IOExceptionBiConsumer<Request, HttpSession> task) {
        this.request = request;
        this.session = session;
        this.task = task;
    }

    @Override
    public void run() {
        try {
            task.accept(request, session);
        } catch (IOException ioException) {
            closeSession(session, ioException);
        } catch (Exception exception) {
            log.severe(exception.getMessage());
            sendResponseWithoutIo(session, Responses.INTERNAL_ERROR);
        }
    }
}
