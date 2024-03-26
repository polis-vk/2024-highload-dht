package ru.vk.itmo.test.kislovdanil.service.sharding;

import one.nio.http.Request;
import one.nio.http.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public abstract class BaseSharder implements Sharder {
    private static final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
    private final HttpClient client;
    protected static final String TIMESTAMP_HEADER = "X-Timestamp";

    @Override
    public String getTimestampHeader() {
        return TIMESTAMP_HEADER;
    }

    protected BaseSharder(HttpClient client) {
        this.client = client;
    }

    protected Response handleProxiedResponse(int statusCode, byte[] body, Long timestamp) {
        Response response = switch (statusCode) {
            case 200 -> new Response(Response.OK, body);
            case 201 -> new Response(Response.CREATED, Response.EMPTY);
            case 202 -> new Response(Response.ACCEPTED, Response.EMPTY);
            case 404 -> new Response(Response.NOT_FOUND, Response.EMPTY);
            case 400 -> new Response(Response.BAD_REQUEST, Response.EMPTY);
            default -> new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        };
        if (timestamp != null) {
            response.addHeader(TIMESTAMP_HEADER + " " + timestamp);
        }
        return response;
    }

    private String requestMethodNumberToString(int method) {
        return switch (method) {
            case Request.METHOD_GET -> "GET";
            case Request.METHOD_PUT -> "PUT";
            case Request.METHOD_DELETE -> "DELETE";
            default -> throw new IllegalArgumentException();
        };
    }

    private Long tryToExtractTimestampHeader(HttpResponse<byte[]> response) {
        if (response.statusCode() == 200 || response.statusCode() == 404) {
            return Long.parseLong(response.headers().map().get(TIMESTAMP_HEADER).getFirst());
        }
        return null;
    }

    private Response handleSendingException(HttpResponse<byte[]> response,
                                            Throwable exception) {
        return exception == null ? handleProxiedResponse(response.statusCode(),
                response.body(),
                tryToExtractTimestampHeader(response)) :
                new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
    }

    @Override
    public List<CompletableFuture<Response>> proxyRequest(int method, String entityKey, byte[] body,
                                                          List<String> baseUrls) {
        List<CompletableFuture<Response>> futures = new ArrayList<>(baseUrls.size());
        for (String baseUrl : baseUrls) {
            String entityUrl = baseUrl + "/v0/entity?id=" + entityKey + "&not-proxy=true";
            URI uri = URI.create(entityUrl);
            futures.add(client.sendAsync(requestBuilder
                            .uri(uri)
                            .method(requestMethodNumberToString(method),
                                    body == null ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofByteArray(body))
                            .timeout(Duration.ofMillis(50)).build(),
                    HttpResponse.BodyHandlers.ofByteArray()).handleAsync(this::handleSendingException));
        }
        return futures;
    }
}
