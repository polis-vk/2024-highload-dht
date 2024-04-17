package ru.vk.itmo.test.kovalevigor.server.util;

import one.nio.http.HttpSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;

public final class ServerUtil {

    public static final Set<Integer> GOOD_STATUSES = Set.of(
            200, 201, 202, 404
    );

    private ServerUtil() {
    }

    public static void sendResponseWithoutIo(HttpSession session, Responses response) {
        try {
            session.sendResponse(response.toResponse());
        } catch (IOException ioException) {
            log.log(Level.SEVERE, "IO in socket", ioException);
            closeSession(session, ioException);
        }
    }

    public static void closeSession(HttpSession session, Exception base) {
        try {
            session.sendError(Responses.SERVICE_UNAVAILABLE.getResponseCode(), null);
        } catch (IOException ioException) {
            ioException.addSuppressed(base);
            log.log(Level.SEVERE, "IO in socket", ioException);
            session.handleException(ioException);
        }
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
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

    public static int compareTimestamps(String lhs, String rhs) {
        if (lhs == null && rhs == null) {
            return 0;
        } else if (lhs == null) {
            return -1;
        } else if (rhs == null) {
            return 1;
        }
        if (lhs.length() != rhs.length()) {
            return Integer.compare(lhs.length(), rhs.length());
        }
        for (int i = 0; i < lhs.length(); i++) {
            int charCompare = Character.compare(lhs.charAt(i), rhs.charAt(i));
            if (charCompare != 0) {
                return charCompare;
            }
        }
        return 0;
    }
}
