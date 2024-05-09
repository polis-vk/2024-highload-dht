package ru.vk.itmo.test.kovalevigor.server.util;

import one.nio.http.HttpSession;
import one.nio.http.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;

public final class ServerUtil {

    public static final TimeUnit SHUTDOWN_TIMEOUT_TIME_UNIT = TimeUnit.SECONDS;
    public static final int SHUTDOWN_TIMEOUT = 60;
    public static final Set<Integer> GOOD_STATUSES = Set.of(
            200, 201, 202, 404
    );

    public static final TimeUnit REMOTE_TIMEOUT_TIMEUNIT = TimeUnit.MILLISECONDS;
    public static final int REMOTE_TIMEOUT_VALUE = 500;
    public static final Duration REMOTE_TIMEOUT = Duration.ofMillis(500);

    private ServerUtil() {
    }

    public static void sendResponseWithoutIo(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException ioException) {
            logIO(ioException);
            closeSession(session, ioException);
        }
    }

    public static void sendResponseWithoutIo(HttpSession session, Responses response) {
        sendResponseWithoutIo(session, response.toResponse());
    }

    public static void sendErrorWithoutIo(HttpSession session, String code, String message) {
        try {
            session.sendError(code, message);
        } catch (IOException ioException) {
            logIO(ioException);
            closeSession(session, ioException);
        }
    }

    public static void logIO(IOException exception) {
        log.log(Level.SEVERE, "IO in socket", exception);
    }

    public static void closeSession(HttpSession session, Exception base) {
        try {
            session.sendError(Responses.SERVICE_UNAVAILABLE.getResponseCode(), null);
        } catch (IOException ioException) {
            ioException.addSuppressed(base);
            logIO(ioException);
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

    public static Response mergeResponses(Response lhs, Response rhs) {
        if (lhs == null) {
            return rhs;
        } else if (rhs == null) {
            return lhs;
        }

        int compare = compareTimestamps(getTimestamp(lhs), getTimestamp(rhs));
        if (compare == 0) {
            return rhs;
        }
        return compare > 0 ? lhs : rhs;
    }

    public static String getTimestamp(Response response) {
        return Headers.getHeader(response, Headers.TIMESTAMP);
    }
}
