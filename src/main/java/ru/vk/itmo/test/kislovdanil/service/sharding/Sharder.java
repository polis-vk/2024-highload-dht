package ru.vk.itmo.test.kislovdanil.service.sharding;

import one.nio.http.HttpSession;
import one.nio.http.Response;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Sharder {
    List<String> defineRequestProxyUrls(String entityKey, int from);

    List<CompletableFuture<Response>> proxyRequest(int method, String entityKey, byte[] body,
                                                   List<String> baseUrls);

    Response makeDecision(List<Response> responses, int acknowledge, int method);

    String getTimestampHeader();
}
