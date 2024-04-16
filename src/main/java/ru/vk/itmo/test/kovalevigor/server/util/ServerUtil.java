package ru.vk.itmo.test.kovalevigor.server.util;

import one.nio.http.HttpSession;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;

public final class ServerUtil {

    public static final TimeUnit SHUTDOWN_TIMEOUT_TIME_UNIT = TimeUnit.SECONDS;
    public static final int SHUTDOWN_TIMEOUT = 60;

    private ServerUtil() {
    }

    public static void sendResponseWithoutIo(HttpSession session, Responses response) {
        try {
            session.sendResponse(response.toResponse());
        } catch (IOException ioException) {
            closeSession(session, ioException);
        }
    }

    public static void closeSession(HttpSession session, Exception base) {
        try {
            session.sendError(Responses.SERVICE_UNAVAILABLE.getResponseCode(), null);
        } catch (IOException ioException) {
            ioException.addSuppressed(base);
            session.handleException(ioException);
        }
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(SHUTDOWN_TIMEOUT, SHUTDOWN_TIMEOUT_TIME_UNIT)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(SHUTDOWN_TIMEOUT, SHUTDOWN_TIMEOUT_TIME_UNIT)) {
                    log.severe("Pool did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    public static RuntimeException createIllegalState() {
        return new IllegalStateException("Can't be");
    }
}
