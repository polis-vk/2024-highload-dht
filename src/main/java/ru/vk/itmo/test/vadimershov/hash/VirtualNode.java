package ru.vk.itmo.test.vadimershov.hash;

import one.nio.http.HttpClient;

import javax.annotation.Nonnull;

public class VirtualNode {
    private final HttpClient httpClient;
    private final String url;
    private final int replicaIndex;

    public VirtualNode(String url, @Nonnull HttpClient httpClient, int replicaIndex) {
        this.httpClient = httpClient;
        this.url = url;
        this.replicaIndex = replicaIndex;
    }

    public HttpClient httpClient() {
        return this.httpClient;
    }

    public String url() {
        return this.url;
    }

    public String key() {
        return "Virtual node: " + this.url + "-" + this.replicaIndex;
    }

    public void close() {
        this.httpClient.close();
    }
}
