package ru.vk.itmo.test.tyapuevdmitrij;

import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class Client {
    private static final Logger LOGGER = LoggerFactory.getLogger(Client.class);

    public static final String PROXY_TIMESTAMP_HEADER = "proxy";

    public static final String NODE_TIMESTAMP_HEADER = "node";

    private static final byte[] EMPTY_BODY = new byte[0];
    private final HttpClient httpClient;

    private String url;

    private long timeStamp;

    private final ExecutorService proxyExecutor;

    Client(ExecutorService proxyExecutor) {
        this.httpClient = HttpClient.newBuilder().build();
        this.proxyExecutor = proxyExecutor;
    }

    public CompletableFuture<Response> handleProxyRequest(Request request) {
        CompletableFuture<HttpResponse<byte[]>> responseFuture = getProxyResponse(request);
        return responseFuture.thenApplyAsync(response -> {
            if (response == null) {
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }
            String statusCode = switch (response.statusCode()) {
                case HttpURLConnection.HTTP_OK -> Response.OK;
                case HttpURLConnection.HTTP_CREATED -> Response.CREATED;
                case HttpURLConnection.HTTP_ACCEPTED -> Response.ACCEPTED;
                case HttpURLConnection.HTTP_BAD_REQUEST -> Response.BAD_REQUEST;
                case HttpURLConnection.HTTP_NOT_FOUND -> Response.NOT_FOUND;
                case HttpURLConnection.HTTP_INTERNAL_ERROR -> Response.INTERNAL_ERROR;
                default -> throw new IllegalStateException("Unexpected value: " + response.statusCode());
            };
            Optional<String> nodeHeader = response.headers().firstValue(NODE_TIMESTAMP_HEADER);
            Response finalResponse = new Response(statusCode, response.body());
            if (nodeHeader.isPresent()) {
                long time = Long.parseLong(nodeHeader.get());
                finalResponse.addHeader(Client.NODE_TIMESTAMP_HEADER + ":" + time);
            }
            return finalResponse;
        }, proxyExecutor).exceptionally(throwable -> {
            LOGGER.error("can't reach target node", throwable);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        });
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    private byte[] getRequestBody(Request request) {
        return request.getBody() == null ? EMPTY_BODY : request.getBody();
    }

    private HttpRequest getProxyRequest(Request request) {
        return HttpRequest.newBuilder(URI.create(url + request.getURI()))
                .method(request.getMethodName(),
                        HttpRequest.BodyPublishers.ofByteArray(getRequestBody(request)))
                .header(PROXY_TIMESTAMP_HEADER, String.valueOf(timeStamp))
                .build();
    }

    private CompletableFuture<HttpResponse<byte[]>> getProxyResponse(Request request) {
        return httpClient.sendAsync(getProxyRequest(request),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
