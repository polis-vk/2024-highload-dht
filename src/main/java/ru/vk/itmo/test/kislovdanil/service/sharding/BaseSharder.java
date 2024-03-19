package ru.vk.itmo.test.kislovdanil.service.sharding;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kislovdanil.service.DatabaseHttpServer;
import ru.vk.itmo.test.kislovdanil.service.NetworkException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public abstract class BaseSharder implements Sharder {
    private final HttpClient client;
    private static final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();

    protected BaseSharder(HttpClient client) {
        this.client = client;
    }

    private String requestMethodNumberToString(int method) {
        return switch (method) {
            case Request.METHOD_GET -> "GET";
            case Request.METHOD_PUT -> "PUT";
            case Request.METHOD_DELETE -> "DELETE";
            default -> throw new IllegalArgumentException();
        };
    }

    private static void handleProxiedResponse(HttpResponse<byte[]> response, HttpSession session) {
        Response finalResponse = switch (response.statusCode()) {
            case 200 -> new Response(Response.OK, response.body());
            case 201 -> new Response(Response.CREATED, Response.EMPTY);
            case 202 -> new Response(Response.ACCEPTED, Response.EMPTY);
            case 404 -> new Response(Response.NOT_FOUND, Response.EMPTY);
            case 400 -> new Response(Response.BAD_REQUEST, Response.EMPTY);
            default -> new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        };
        try {
            session.sendResponse(finalResponse);
        } catch (IOException e) {
            throw new NetworkException();
        }
    }

    private HttpResponse<byte[]> handleSendingException(HttpResponse<byte[]> response,
                                                        Throwable exception, HttpSession session) {
        if (exception != null) {
            DatabaseHttpServer.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY), session);
            throw new NetworkException();
        }
        return response;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    // Основные исключения будут обработаны, Runtime исключения при обработке одного запроса можно и проигнорировать
    public void proxyRequest(int method, String entityKey, byte[] body, String baseUrl, HttpSession session) {
        String entityUrl = baseUrl + "/v0/entity?id=" + entityKey;
        URI uri = URI.create(entityUrl);
        client.sendAsync(requestBuilder
                                .uri(uri)
                                .method(requestMethodNumberToString(method),
                                        body == null ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofByteArray(body))
                                .timeout(Duration.ofMillis(50)).build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, exception) -> handleSendingException(response, exception, session))
                .thenAccept(response -> handleProxiedResponse(response, session));
    }
}
