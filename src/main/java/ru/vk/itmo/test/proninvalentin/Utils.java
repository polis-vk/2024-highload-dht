package ru.vk.itmo.test.proninvalentin;

import one.nio.http.Request;
import one.nio.http.Response;

import java.net.HttpURLConnection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class Utils {
    private static final int SOFT_SHUT_DOWN_TIME = 20;
    private static final int HARD_SHUT_DOWN_TIME = 10;
    private static final Set<Integer> SUPPORTED_HTTP_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );
    public static final Map<Integer, String> httpCodeMapping = Map.of(
            HttpURLConnection.HTTP_OK, Response.OK,
            HttpURLConnection.HTTP_ACCEPTED, Response.ACCEPTED,
            HttpURLConnection.HTTP_CREATED, Response.CREATED,
            HttpURLConnection.HTTP_BAD_REQUEST, Response.BAD_REQUEST,
            HttpURLConnection.HTTP_INTERNAL_ERROR, Response.INTERNAL_ERROR,
            HttpURLConnection.HTTP_NOT_FOUND, Response.NOT_FOUND,
            HttpURLConnection.HTTP_CLIENT_TIMEOUT, Response.REQUEST_TIMEOUT
    );

    private Utils() {
    }

    public static boolean isSupportedMethod(int httpMethod) {
        return SUPPORTED_HTTP_METHODS.contains(httpMethod);
    }

    public static void shutdownGracefully(ExecutorService pool) {
        pool.shutdown();
        try {
            pool.awaitTermination(SOFT_SHUT_DOWN_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        pool.shutdownNow();
        try {
            pool.awaitTermination(HARD_SHUT_DOWN_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean isNullOrBlank(String str) {
        return str == null || str.isBlank();
    }
}
