package ru.vk.itmo.test.khadyrovalmasgali.hashing;

import one.nio.http.HttpClient;
import one.nio.net.ConnectionString;
import one.nio.util.Hash;

public class Node {
    private final String url;
    private final HttpClient client;
    private final int weight;

    public Node(String url) {
        this.url = url;
        this.client = new HttpClient(new ConnectionString(url));
        this.weight = 100;
    }

    public Node(String url, int weight) {
        this.url = url;
        this.client = new HttpClient(new ConnectionString(url));
        this.weight = weight;
    }

    public String getUrl() {
        return url;
    }

    public HttpClient getClient() {
        return client;
    }

    public int computeScore(String key) {
        int score = Hash.murmur3(url + key);
        return score * weight;
    }
}
