package ru.vk.itmo.test.alenkovayulya;

import one.nio.async.CustomThreadFactory;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ShardRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardRouter.class);
    public static final String PATH = "/v0/entity";
    public static final String TIMESTAMP_HEADER = "Timestamp";
    public static final String REDIRECT_HEADER = "Redirect";
    private static final HttpClient client = HttpClient.newHttpClient();

    public static ThreadPoolExecutor proxyExecutor = new ThreadPoolExecutor(
            8,
            8,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),
            new CustomThreadFactory("ShardRouter"));

    private ShardRouter() {
    }

    public static CompletableFuture<Response> redirectRequest(String method,
                                           String id,
                                           String ownerShardUrl,
                                           byte[] body,
                                           long timestamp) {
        String path = ownerShardUrl + PATH + "?id=" + id;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(path))
                .header(REDIRECT_HEADER, "true")
                .header(TIMESTAMP_HEADER, String.valueOf(timestamp))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.thenApplyAsync(ShardRouter::getHttpResponseByCode);
        } catch (Exception e) {
            LOGGER.error("Error during sending request by router", e);
            Thread.currentThread().interrupt();
            return CompletableFuture.completedFuture(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }

    private static Response getHttpResponseByCode(HttpResponse<byte[]> response) {
        String responseCode = switch (response.statusCode()) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            case 500 -> Response.INTERNAL_ERROR;
            default -> throw new IllegalStateException("Not available status code: " + response.statusCode());
        };

        Response shardResponse = new Response(responseCode, response.body());
        shardResponse.addHeader(response.headers().firstValue(TIMESTAMP_HEADER).orElse(""));

        return shardResponse;

    }
}
