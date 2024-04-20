package ru.vk.itmo.test.proninvalentin.utils;

import one.nio.http.HttpServerConfig;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.List;
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

    public static Map<String, HttpRequest> buildRequests(Request request, List<String> nodeUrls, String entryId) {
        HashMap<String, HttpRequest> httpRequests = new HashMap<>(nodeUrls.size());
        for (String nodeUrl : nodeUrls) {
            httpRequests.put(nodeUrl, buildProxyRequest(request, nodeUrl, entryId));
        }
        return httpRequests;
    }

    public static HttpRequest buildProxyRequest(Request request, String nodeUrl, String parameter) {
        byte[] body = request.getBody();
        return HttpRequest.newBuilder(URI.create(nodeUrl + Constants.REQUEST_PATH + parameter))
                .method(request.getMethodName(), body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .setHeader(Constants.TERMINATION_HEADER, Constants.TERMINATION_TRUE)
                .build();
    }

    public static boolean hasHandler(Request request) {
        return isSingleRequest(request) || isRangeRequest(request);
    }

    private static boolean isSingleRequest(Request request) {
        return request.getURI().startsWith(Constants.REQUEST_PATH);
    }

    public static boolean isRangeRequest(Request request) {
        return request.getURI().startsWith(Constants.RANGE_REQUEST_PATH);
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

    public static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }
}
