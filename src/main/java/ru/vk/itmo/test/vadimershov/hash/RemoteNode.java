package ru.vk.itmo.test.vadimershov.hash;

import ru.vk.itmo.test.vadimershov.ResultResponse;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;

import javax.annotation.Nonnull;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

public class RemoteNode extends VirtualNode {

    public static final String X_INNER = "X-inner";
    public static final String ENTITY_URI = "/v0/entity?id=";
    private static final String X_TIMESTAMP = "X-timestamp";

    private final String uriPrefix;
    private final HttpClient httpClient;

    public RemoteNode(String url, @Nonnull HttpClient httpClient, int replicaIndex) {
        super(url, replicaIndex);
        this.httpClient = httpClient;
        this.uriPrefix = url + ENTITY_URI;
    }

    @Override
    public void close() {
        if (!this.httpClient.isTerminated()) {
            this.httpClient.close();
        }
    }

    @Override
    public CompletableFuture<ResultResponse> get(String key) {
        return this.httpClient.sendAsync(
                        HttpRequest.newBuilder(URI.create(uriPrefix + key))
                                .GET()
                                .header(X_INNER, "true")
                                .timeout(Duration.ofMillis(100))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .thenApply((httpResponse) ->
                        new ResultResponse(httpResponse.statusCode(), httpResponse.body(), getTimestamp(httpResponse))
                );
    }

    private static Long getTimestamp(HttpResponse<byte[]> response) {
        return response.headers()
                .firstValueAsLong(X_TIMESTAMP)
                .orElse(0L);
    }

    @Override
    public CompletableFuture<ResultResponse> upsert(String key, byte[] value, Long timestamp) {
        return this.httpClient.sendAsync(
                        HttpRequest.newBuilder(URI.create(uriPrefix + key))
                                .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
                                .header(X_INNER, "true")
                                .header(X_TIMESTAMP, timestamp.toString())
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .thenApply((httpResponse) ->
                        new ResultResponse(httpResponse.statusCode(), null, 0L)
                );
    }

    @Override
    public CompletableFuture<ResultResponse> delete(String key, Long timestamp) {
        return this.httpClient.sendAsync(
                        HttpRequest.newBuilder(URI.create(uriPrefix + key))
                                .DELETE()
                                .headers(X_INNER, "true")
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .thenApply((httpResponse) ->
                        new ResultResponse(httpResponse.statusCode(), null, 0L)
                );
    }

    @Override
    public Iterator<TimestampEntry<MemorySegment>> range(String start, String end) {
        throw new UnsupportedOperationException();
    }

}
