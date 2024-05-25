package ru.vk.itmo.test.kovalevigor.server.strategy;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.Headers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;

import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;
import static ru.vk.itmo.test.kovalevigor.server.strategy.util.ServerUtil.REMOTE_TIMEOUT;
import static ru.vk.itmo.test.kovalevigor.server.strategy.util.ServerUtil.REMOTE_TIMEOUT_TIMEUNIT;
import static ru.vk.itmo.test.kovalevigor.server.strategy.util.ServerUtil.REMOTE_TIMEOUT_VALUE;

public class ServerRemoteStrategy extends ServerRejectStrategy {
    private final HttpClient httpClient;
    private final String remoteUrl;

    public ServerRemoteStrategy(HttpClient httpClient, String remoteUrl) {
        this.httpClient = httpClient;
        this.remoteUrl = remoteUrl;
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) {
        try {
            return mapResponse(httpClient.send(mapRequest(request), HttpResponse.BodyHandlers.ofByteArray()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.log(Level.SEVERE, "Exception while redirection", e);
        }
        return null;
    }

    @Override
    public CompletableFuture<Response> handleRequestAsync(Request request, HttpSession session, Executor executor) {
        return httpClient.sendAsync(mapRequest(request), HttpResponse.BodyHandlers.ofByteArray())
                .orTimeout(REMOTE_TIMEOUT_VALUE, REMOTE_TIMEOUT_TIMEUNIT)
                .thenApplyAsync(ServerRemoteStrategy::mapResponse, executor);
    }

    private HttpRequest mapRequest(Request request) {
        HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder(URI.create(remoteUrl + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .timeout(REMOTE_TIMEOUT);
        for (Headers header : Headers.values()) {
            String headerValue = Headers.getHeader(request, header);
            if (headerValue != null) {
                httpRequestBuilder.header(header.getName(), headerValue);
            }
        }
        return httpRequestBuilder.build();
    }

    private static Response mapResponse(HttpResponse<byte[]> httpResponse) {
        Response response = new Response(
                Integer.toString(httpResponse.statusCode()),
                httpResponse.body()
        );
        for (Headers header : Headers.values()) {
            Headers.addHeader(response, header, httpResponse.headers().firstValue(header.getName()));
        }
        return response;
    }
}
