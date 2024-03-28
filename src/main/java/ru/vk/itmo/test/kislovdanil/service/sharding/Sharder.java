package ru.vk.itmo.test.kislovdanil.service.sharding;

import one.nio.http.HttpSession;

public interface Sharder {
    String defineRequestProxyUrl(String entityKey);

    void proxyRequest(int method, String entityKey, byte[] body, String baseUrl, HttpSession session);
}
