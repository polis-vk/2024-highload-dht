package ru.vk.itmo.test.kislovdanil.service.sharding;

import one.nio.http.Response;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Sharder {
    String TIMESTAMP_HEADER = "X-Timestamp";
    String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";

    List<String> defineRequestProxyUrls(String entityKey, int from);

    List<CompletableFuture<Response>> proxyRequest(int method, String entityKey, byte[] body,
                                                   List<String> baseUrls);
}
