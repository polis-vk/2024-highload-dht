package ru.vk.itmo.test.vadimershov.hash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.vadimershov.ResultResponse;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class RemoteNode extends VirtualNode {

    public static final String X_INNER = "X-inner";
    public static final String ENTITY_URI = "/v0/entity?id=";
    private static final String X_TIMESTAMP = "X-timestamp";

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

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
                .thenApply((httpResponse) -> {
                    logger.info("" + httpResponse);
                    return new ResultResponse(httpResponse.statusCode(), httpResponse.body(), getTimestamp(httpResponse));
                });
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
                .thenApply((httpResponse) -> {

                    logger.info("" + httpResponse);
                    return new ResultResponse(httpResponse.statusCode(), null, 0L);
                });
    }

    @Override
    public CompletableFuture<ResultResponse> delete(String key, Long timestamp) {
        return this.httpClient.sendAsync(
                        HttpRequest.newBuilder(URI.create(uriPrefix + key))
                                .DELETE()
                                .headers(X_INNER, "true")
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .thenApply((httpResponse) -> {

                    logger.info("" + httpResponse);
                    return new ResultResponse(httpResponse.statusCode(), null, 0L);
                });
    }

//    private void checkCodeInRemoteResp(String url, HttpResponse response) throws RemoteServiceException {
//        switch (response.statusCode()) {
//            case 200, 201, 202, 404 -> { /* correct http code */ }
//            case 400 -> throw new RemoteServiceException(DaoResponse.BAD_REQUEST, url);
//            case 405 -> throw new RemoteServiceException(DaoResponse.METHOD_NOT_ALLOWED, url);
//            case 429 -> throw new RemoteServiceException(DaoResponse.NOT_ENOUGH_REPLICAS, url);
//            case 503 -> throw new RemoteServiceException(DaoResponse.SERVICE_UNAVAILABLE, url);
//            default -> throw new RemoteServiceException(DaoResponse.INTERNAL_ERROR, url);
//        }
//    }

}
