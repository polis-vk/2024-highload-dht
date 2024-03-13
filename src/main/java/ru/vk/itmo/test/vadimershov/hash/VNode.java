package ru.vk.itmo.test.vadimershov.hash;

import one.nio.http.HttpClient;
import one.nio.net.ConnectionString;

public class VNode {
    private final HttpClient httpClient;
    private final String url;
    private final int replicaIndex;

    public VNode(String url, int replicaIndex) {
        this.httpClient = new HttpClient(new ConnectionString(url));
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
}
