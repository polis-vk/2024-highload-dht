package ru.vk.itmo.test.khadyrovalmasgali.hashing;

import one.nio.http.HttpClient;
import one.nio.net.ConnectionString;
import one.nio.util.Hash;

public class Node {
    private final String url;
    private final int weight;

    public Node(String url) {
        this.url = url;
        this.weight = 1;
    }

    public Node(String url, int weight) {
        this.url = url;
        this.weight = weight;
    }

    public String getUrl() {
        return url;
    }

    public int computeScore(String key) {
        int score = Hash.murmur3(url + key);
        return score * weight;
    }
}
