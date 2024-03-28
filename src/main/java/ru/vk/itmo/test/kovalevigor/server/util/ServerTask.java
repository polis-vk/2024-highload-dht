package ru.vk.itmo.test.kovalevigor.server.util;

import one.nio.http.HttpSession;
import one.nio.http.Request;

import java.io.IOException;
import java.util.logging.Level;

import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.closeSession;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.sendResponseWithoutIo;

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
            log.log(Level.SEVERE, "IO while executing", ioException);
            closeSession(session, ioException);
        } catch (Exception exception) {
            log.log(Level.SEVERE, "Exception while executing", exception);
            sendResponseWithoutIo(session, Responses.INTERNAL_ERROR);
        }
    }
}
